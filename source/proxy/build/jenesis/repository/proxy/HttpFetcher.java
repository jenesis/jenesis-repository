package build.jenesis.repository.proxy;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.Origins;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.net.PrivateHosts;
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
 * read, so a truncated response is never cached as complete. One minute by default, or
 * {@code jenrepo.proxy.request-timeout} ({@code PT30S}, {@code 30s}).
 *
 * <p>Redirects are followed by hand rather than by the JDK's {@code NORMAL} policy, which re-sends
 * {@code Authorization} across a change of host: an import or proxy fetch may redirect to a presigned object-store URL
 * or a CDN, and the operator's credentials must not travel there. A redirect leaving the origin drops the sensitive
 * headers, as a browser or {@code docker} does.
 *
 * <p>Each upstream-chosen hop is also re-judged by the shared {@link PrivateHosts} screen, since a public URL could
 * otherwise redirect to {@code 169.254.169.254} or a loopback control plane: a hop to a private, loopback, link-local,
 * site-local, CGNAT, multicast or unique-local host fails with an {@link IOException} rather than being fetched. The
 * initial URL is the trigger's or the operator's to judge. Every hop goes through the product's HTTP client, which
 * holds an admitted host to public addresses, so a rebinding name is refused.
 */
public final class HttpFetcher implements ProxyFormat.Fetcher {

    /** Headers carrying a caller credential, dropped when a redirect crosses to another origin. */
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "proxy-authorization", "cookie", "jenesis-repository-key");

    /** A bound on the redirect chain, so a redirect loop cannot spin an import or a proxy fetch forever. */
    private static final int MAX_REDIRECTS = 5;

    /** A ceiling on a buffered {@link #fetch} body - the small mutable index path (a packument, maven-metadata, an OCI
     *  manifest, a tags page, a token document) - so a hostile or substituted upstream cannot return a multi-GB "index"
     *  and exhaust the heap before any cache cap applies. Far above any legitimate index; the streaming
     *  {@link #download} path copies network-to-store unbuffered and is uncapped. */
    private static final int MAX_FETCH_BODY = 64 * 1024 * 1024;

    private final HttpClient client = ScreenedHttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .throughputFloor(HttpFetcher::throughputFloor, ScreenedHttpClient.FLOOR_WINDOW)
            .deadline(HttpFetcher::deadline)
            .build();
    private final Duration requestTimeout;
    /** The SSRF screen applied to each redirect target's host; {@code true} refuses the hop.
     *  {@link PrivateHosts#resolvesToPrivate} in a deployment; a test injects a permissive one to drive redirects
     *  against a loopback fixture. */
    private final Predicate<String> blockedRedirectHost;

    /** The default fetcher: a per-request timeout from {@code jenrepo.proxy.request-timeout}, or one minute. */
    public HttpFetcher() {
        this(requestTimeout());
    }

    /** A fetcher with an explicit per-request timeout (a test's seam for a stalled upstream), screening redirect
     *  targets with {@link PrivateHosts}. */
    public HttpFetcher(Duration requestTimeout) {
        this(requestTimeout, PrivateHosts::resolvesToPrivate);
    }

    /** A fetcher with an explicit timeout and redirect-host screen - a test's seam for redirects against a loopback
     *  fixture or for the private-host refusal. */
    public HttpFetcher(Duration requestTimeout, Predicate<String> blockedRedirectHost) {
        this.requestTimeout = requestTimeout;
        this.blockedRedirectHost = blockedRedirectHost;
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
     *  exchange. Redirects follow the same manual chain, credentials dropped on a cross-origin hop, reissuing
     *  {@code HEAD} at each; the discarding handler buffers nothing even if an upstream sends a body. */
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

    /** Issue the request with {@code method} ({@code GET} or {@code HEAD}) and follow redirects by hand, dropping the
     *  {@link #SENSITIVE} headers once the chain leaves the original origin; the method is kept across hops. An
     *  intermediate redirect's body is closed; the final response returns with its body intact. */
    private <T> HttpResponse<T> send(URI url, Map<String, String> requestHeaders, String method,
                                     HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        URI origin = url;
        URI current = url;
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
        for (int redirect = 0; ; redirect++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(current).timeout(requestTimeout)
                    .method(method, HttpRequest.BodyPublishers.noBody());
            headers.forEach(request::header);
            HttpResponse<T> response = client.send(request.build(), handler);
            Optional<String> location = redirect < MAX_REDIRECTS && isRedirect(response.statusCode())
                    ? response.headers().firstValue("Location")
                    : Optional.empty();
            if (location.isEmpty()) {
                return response;
            }
            if (response.body() instanceof Closeable body) {
                body.close(); // release the intermediate redirect's connection before the next hop
            }
            current = current.resolve(location.get());
            // The scheme first, since the host screen cannot judge a URI without a host: `Location: file:///etc/passwd`
            // has a null host, which the classifier admits, and a non-http(s) URI would make HttpRequest.newBuilder
            // throw an unchecked IllegalArgumentException, turning an upstream's header into a 500. A redirect off
            // http(s) is never followed.
            String scheme = current.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IOException("refusing to follow a redirect off http(s), which is not a scheme an upstream "
                        + "fetch may take: " + current.getScheme() + " (from " + location.get() + ")");
            }
            if (current.getHost() == null || current.getHost().isBlank()) {
                throw new IOException("refusing to follow a redirect to a URI carrying no host: " + location.get());
            }
            if (blockedRedirectHost.test(current.getHost())) {
                throw new IOException("refusing to follow a redirect to a private, loopback, link-local or "
                        + "cloud-metadata host (SSRF): " + current.getHost());
            }
            if (!Origins.same(origin, current)) {
                headers.keySet().removeIf(name -> SENSITIVE.contains(name.toLowerCase(Locale.ROOT)));
            }
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
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

    /** The configured per-request timeout, {@code jenrepo.proxy.request-timeout} ({@code PT30S}, {@code 30s}), or a
     *  minute. */
    private static Duration requestTimeout() {
        String value = System.getProperty("jenrepo.proxy.request-timeout");
        return value == null || value.isBlank() ? Duration.ofSeconds(60) : Durations.parse(value);
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
}
