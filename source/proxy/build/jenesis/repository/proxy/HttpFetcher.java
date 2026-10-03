package build.jenesis.repository.proxy;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Durations;

/**
 * The upstream fetch over HTTP: request headers are forwarded, the status and response headers returned.
 * {@link ProxyFormat.Fetcher#download} streams a body straight through, so a large artifact copies from network to
 * storage without materialising, and {@link ProxyFormat.Fetcher#head} issues a real HTTP {@code HEAD}, so probing an
 * uncached artifact never opens its body.
 *
 * <p>Every request is bounded by a per-request timeout on top of the connect timeout, so an upstream that accepts and
 * never answers cannot hang a proxy read or an import. Every way the upstream fails to answer - a timeout, a refused
 * connection, an unresolvable host, a dead route - is the contract's transport failure (an empty result), so a proxy
 * leg reaches clause 2's classification and an import is refused rather than a {@code 5xx} escaping. The timeout bounds
 * the response's arrival, not a large body's transfer; a body ending short of its {@code Content-Length} throws on the
 * read, so a truncated response is never cached as complete. The timeout is the
 * {@value ProxySettingsContributor#REQUEST_TIMEOUT_KEY} setting, read per request.
 *
 * <p>Redirects follow the product's HTTP client's one policy ({@link ScreenedHttpClient}): never from {@code https} to
 * {@code http}, never off http(s), at most five hops, and with the caller's credentials dropped once a hop leaves the
 * origin - an import or a proxy fetch may be redirected to a presigned object-store URL or a CDN, and the operator's
 * credentials must not travel there. A hop the upstream aims at a private, loopback or link-local host from another
 * origin is refused ({@link ScreenedHttpClient.RedirectRefused}), since a public URL could otherwise redirect to
 * {@code 169.254.169.254} or a loopback control plane. The initial URL is the trigger's or the operator's to judge.
 */
public final class HttpFetcher implements ProxyFormat.Fetcher {

    /** A ceiling on a buffered {@link #fetch} body - the small mutable index path (a packument, maven-metadata, an OCI
     *  manifest, a tags page, a token document) - so a hostile or substituted upstream cannot return a multi-GB "index"
     *  and exhaust the heap before any cache cap applies. Far above any legitimate index; the streaming
     *  {@link #download} path copies network-to-store unbuffered and is uncapped. */
    private static final int MAX_FETCH_BODY = 64 * 1024 * 1024;

    private final HttpClient client;
    private final Supplier<Duration> requestTimeout;

    /** The deployment's fetcher: the configured per-request timeout, and no redirect to a private host. */
    public HttpFetcher() {
        this(HttpFetcher::requestTimeout, false);
    }

    /** A fetcher with an explicit per-request timeout (a test's seam for a stalled upstream), refusing a redirect to a
     *  private host. */
    public HttpFetcher(Duration requestTimeout) {
        this(() -> requestTimeout, false);
    }

    /** A fetcher with an explicit timeout that may follow a redirect to a private host - a test's seam for redirects
     *  between loopback fixtures standing in for public hosts. */
    public HttpFetcher(Duration requestTimeout, boolean privateRedirects) {
        this(() -> requestTimeout, privateRedirects);
    }

    private HttpFetcher(Supplier<Duration> requestTimeout, boolean privateRedirects) {
        this.requestTimeout = requestTimeout;
        this.client = ScreenedHttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .redirectsToPrivateHosts(() -> privateRedirects)
                .throughputFloor(HttpFetcher::throughputFloor, ScreenedHttpClient.FLOOR_WINDOW)
                .deadline(HttpFetcher::deadline)
                .build();
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        try {
            // A bounded read rather than ofByteArray(), refusing an oversized body at MAX_FETCH_BODY. Only establishing
            // the exchange folds to the empty answer; a body ending short of its declared length still throws from the
            // read.
            Optional<HttpResponse<InputStream>> response = connect(url, requestHeaders, "GET",
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.isEmpty()) {
                return Optional.empty();
            }
            byte[] body;
            try (InputStream in = response.get().body()) {
                body = in.readNBytes(MAX_FETCH_BODY + 1);
            }
            if (body.length > MAX_FETCH_BODY) {
                throw new IOException("Upstream index body from " + url + " exceeds the " + MAX_FETCH_BODY
                        + "-byte fetch limit - refused (a proxied index must be small metadata, not a bulk artifact).");
            }
            return Optional.of(new ProxyFormat.Fetched(response.get().statusCode(), body, headers(response.get())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        try {
            return connect(url, requestHeaders, "GET", HttpResponse.BodyHandlers.ofInputStream())
                    .map(response -> new ProxyFormat.Download(response.statusCode(), response.body(), headers(response)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    /** A real HTTP {@code HEAD}: status and headers without a body, so an uncached large artifact costs a header
     *  exchange, reissued as {@code HEAD} at each redirect; the discarding handler buffers nothing even if an
     *  upstream sends a body. */
    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
        try {
            return connect(url, requestHeaders, "HEAD", HttpResponse.BodyHandlers.discarding())
                    .map(response -> new ProxyFormat.Head(response.statusCode(), headers(response)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    /**
     * Open the exchange, reporting every way the upstream can fail to answer as the contract's transport failure -
     * clause 6's empty {@link Optional} - rather than an exception. A refused connection, an unresolvable host or a
     * dead route as a raw {@link IOException} would be indistinguishable from a malformed index, and would bypass
     * clause 2's classification and {@code ProxyRelay}'s enumeration-versus-pinned split - serving a module whose
     * mirror refused the connection as one with no versions.
     *
     * <p>Deliberately narrow: only establishing the exchange folds. A body dying mid-transfer still throws, a TLS
     * handshake that will not complete propagates as the misconfiguration it is, and the redirect chain's private-host
     * refusal stays a visible {@link IOException}.
     */
    private <T> Optional<HttpResponse<T>> connect(URI url, Map<String, String> requestHeaders, String method,
                                                  HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        try {
            return Optional.of(send(url, requestHeaders, method, handler));
        } catch (HttpTimeoutException | SocketException | UnknownHostException _) {
            return Optional.empty();
        }
    }

    /** Issue the request with {@code method} ({@code GET} or {@code HEAD}); the client follows its redirects. */
    private <T> HttpResponse<T> send(URI url, Map<String, String> requestHeaders, String method,
                                     HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(requestTimeout.get())
                .method(method, HttpRequest.BodyPublishers.noBody());
        requestHeaders.forEach(request::header);
        return client.send(request.build(), handler);
    }

    /** The throughput floor an upstream fetch is held to, read now: {@link ProxySettingsContributor#FLOOR_KEY}, else
     *  the client's; an unparseable or negative value is the client's, not none. */
    static long throughputFloor() {
        String configured = Features.lookup().apply("jenrepo." + ProxySettingsContributor.FLOOR_KEY);
        if (configured == null || configured.isBlank()) {
            return ScreenedHttpClient.THROUGHPUT_FLOOR;
        }
        try {
            long floor = Long.parseLong(configured.trim());
            return floor < 0 ? ScreenedHttpClient.THROUGHPUT_FLOOR : floor;
        } catch (NumberFormatException notANumber) {
            return ScreenedHttpClient.THROUGHPUT_FLOOR;
        }
    }

    /** The deadline on one upstream fetch, read now: {@link ProxySettingsContributor#DEADLINE_KEY}, else none; an
     *  unparseable value is none, as the default is. */
    static Duration deadline() {
        String configured = Features.lookup().apply("jenrepo." + ProxySettingsContributor.DEADLINE_KEY);
        if (configured == null || configured.isBlank()) {
            return Duration.ZERO;
        }
        try {
            return Durations.parse(configured);
        } catch (IllegalArgumentException malformed) {
            return Duration.ZERO;
        }
    }

    /** The per-request timeout, read now: {@link ProxySettingsContributor#REQUEST_TIMEOUT_KEY}, else its default;
     *  an unparseable or non-positive value is the default. */
    static Duration requestTimeout() {
        Duration fallback = Durations.parse(ProxySettingsContributor.REQUEST_TIMEOUT_TEXT);
        String configured = Features.lookup().apply("jenrepo." + ProxySettingsContributor.REQUEST_TIMEOUT_KEY);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            Duration timeout = Durations.parse(configured);
            return timeout.isPositive() ? timeout : fallback;
        } catch (IllegalArgumentException malformed) {
            return fallback;
        }
    }

    private static Map<String, String> headers(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });
        return headers;
    }

    @Override
    public ProxyFormat.Fetcher beside() {
        return this;
    }
}
