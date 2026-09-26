package build.jenesis.repository.net.http;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.PrivateHosts;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.ProxyAuthenticationProtocolHandler;
import org.eclipse.jetty.client.RedirectProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;

/**
 * A {@link HttpClient} whose connections are made by Jetty's client: every outbound call this product makes goes
 * through one, built by {@link #newBuilder()} where the JDK's {@code HttpClient.newBuilder()} used to be, and the
 * caller's {@link HttpRequest}, {@link HttpResponse.BodyHandler} and {@link HttpRequest.BodyPublisher} are the JDK's
 * own, bridged onto Jetty's.
 *
 * <p><b>What it changes about a call.</b>
 * <ul>
 *   <li>A host a private-address screen admitted as public is connected to only at a public address
 *       ({@link PrivateHosts#connectable}): a name that rebinds between the screen and the connect is refused with an
 *       {@link IOException} naming it, where the JDK's client would have resolved it again and gone. A host no screen
 *       admitted connects as it resolves.</li>
 *   <li>The request carries {@value #USER_AGENT} as its {@code User-Agent} unless the caller names one, and nothing
 *       about the runtime - no JDK version, no Jetty version, no {@code Accept-Encoding} it did not ask for, and no
 *       {@code HTTP2-Settings} upgrade offer on a cleartext request.</li>
 *   <li>A body is exchanged as sent: nothing is decompressed on the way in, so a proxy relays the upstream's bytes
 *       and their {@code Content-Encoding} together, and nothing names a {@code Content-Type} the caller did not -
 *       a store signing its requests signs the headers it set.</li>
 *   <li>A response is the caller's to read: an authentication challenge is not answered and a {@code 401} without
 *       one is a {@code 401}, as the JDK's client hands them over.</li>
 *   <li>{@link HttpClient.Redirect#NORMAL} follows a redirect as the JDK does - never from {@code https} to
 *       {@code http} - and drops {@code Authorization}, {@code Cookie} and the repository key header when a hop
 *       leaves the origin they were meant for, which the JDK's client does not.</li>
 * </ul>
 *
 * <p>Everything else is the JDK's contract: {@link HttpRequest#timeout()} bounds the wait for the response's
 * headers and answers {@link HttpTimeoutException}, the connect timeout answers {@link HttpConnectTimeoutException},
 * and a body the handler reads streams rather than being buffered. It speaks HTTP/1.1, over TLS where the URL says
 * so, verifying the host name against the default trust material or the {@link SSLContext} the builder is given.
 * A cookie handler, an authenticator and a proxy selector are refused at build time rather than ignored, since no
 * caller uses one and a silently ignored one is a security setting that does not hold.
 *
 * <p>Clients are cheap: every one built with the same connect timeout, trust and resolver shares one Jetty client
 * and its connection pool, which lives as long as the JVM, so {@link #close()} releases nothing and a caller need not
 * hold on to a client to avoid leaking one.
 */
public final class ScreenedHttpClient extends HttpClient {

    /** The {@code User-Agent} every request carries unless its caller names one: the product, and nothing more. */
    public static final String USER_AGENT = "Jenesis-Repository";

    /** Headers carrying a caller's credential, dropped when a followed redirect leaves the original origin. */
    private static final Set<String> SENSITIVE = Set.of("authorization", "proxy-authorization", "cookie",
            "jenesis-repository-key");

    /** The longest redirect chain {@link HttpClient.Redirect#NORMAL} follows, as the JDK's client does. */
    private static final int MAX_REDIRECTS = 5;

    /** How much of a response a body subscriber is handed at a time. */
    private static final int CHUNK = 16 * 1024;

    /** The wait for a response's headers when the request names no timeout, as the JDK's client has none: ten years. */
    private static final long UNBOUNDED = TimeUnit.DAYS.toMillis(3650);

    private static final Map<Engine.Key, Engine> ENGINES = new ConcurrentHashMap<>();

    private final Engine engine;
    private final Duration connectTimeout;
    private final Redirect redirect;
    private final SSLContext sslContext;

    private ScreenedHttpClient(Engine engine, Duration connectTimeout, Redirect redirect, SSLContext sslContext) {
        this.engine = engine;
        this.connectTimeout = connectTimeout;
        this.redirect = redirect;
        this.sslContext = sslContext;
    }

    /** A builder in place of {@code HttpClient.newBuilder()}. */
    public static Builder newBuilder() {
        return new Builder();
    }

    /** A client with the defaults, in place of {@code HttpClient.newHttpClient()}. */
    public static HttpClient newHttpClient() {
        return newBuilder().build();
    }

    /** How a host name becomes addresses: the system resolver unless a test names another. */
    @FunctionalInterface
    public interface Resolver {

        /** The system's resolution of a host. */
        Resolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /** A connection refused because the host now resolves only to addresses a screen refused when it admitted it. */
    public static final class RebindingRefused extends IOException {

        RebindingRefused(String host) {
            super("refusing to connect to " + host + ": a private-address screen admitted it as public, and it now "
                    + "resolves only to private, loopback or link-local addresses (DNS rebinding)");
        }
    }

    // ---- the JDK's accessors ----

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.ofNullable(connectTimeout);
    }

    @Override
    public Redirect followRedirects() {
        return redirect;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return sslContext;
    }

    @Override
    public SSLParameters sslParameters() {
        return sslContext.getDefaultSSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    // ---- sending ----

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        URI origin = request.uri();
        HttpRequest current = request;
        HttpResponse<T> previous = null;
        for (int hop = 0; ; hop++) {
            Exchange exchange = exchange(current);
            Optional<HttpRequest> next = hop < MAX_REDIRECTS ? redirected(origin, current, exchange) : Optional.empty();
            if (next.isEmpty()) {
                return exchange.complete(current, handler, previous);
            }
            exchange.discard();
            // The JDK hands a followed redirect back as a previous response without a body, and so does this.
            previous = new Received<>(exchange.response.getStatus(), current, Optional.ofNullable(previous),
                    Exchange.headers(exchange.response), null, current.uri());
            current = next.get();
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> handler) {
        return sendAsync(request, handler, null);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("jenesis-http-send").start(() -> {
            try {
                result.complete(send(request, handler));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** The request a followed redirect sends next, or empty when this response is the one to answer with. */
    private Optional<HttpRequest> redirected(URI origin, HttpRequest request, Exchange exchange) {
        int status = exchange.response.getStatus();
        if (redirect == Redirect.NEVER || !(status == 301 || status == 302 || status == 303 || status == 307
                || status == 308)) {
            return Optional.empty();
        }
        String location = exchange.response.getHeaders().get("Location");
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        URI target;
        try {
            target = request.uri().resolve(location.strip());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || target.getHost() == null
                || redirect == Redirect.NORMAL && scheme.equals("http")
                && "https".equalsIgnoreCase(request.uri().getScheme())) {
            return Optional.empty();
        }
        boolean keepsBody = status == 307 || status == 308;
        String method = keepsBody || request.method().equals("HEAD") ? request.method() : "GET";
        HttpRequest.Builder next = HttpRequest.newBuilder(target)
                .method(method, keepsBody ? request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())
                        : HttpRequest.BodyPublishers.noBody())
                .expectContinue(request.expectContinue());
        request.timeout().ifPresent(next::timeout);
        boolean sameOrigin = sameOrigin(origin, target);
        request.headers().map().forEach((name, values) -> {
            if (sameOrigin || !SENSITIVE.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> next.header(name, value));
            }
        });
        return Optional.of(next.build());
    }

    /** Send one request and wait for its response's headers, the body left to stream. */
    private Exchange exchange(HttpRequest request) throws IOException, InterruptedException {
        Request outbound = engine.client.newRequest(request.uri())
                .method(request.method())
                .followRedirects(false);
        outbound.headers(headers -> {
            request.headers().map().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
            if (!headers.contains("User-Agent")) {
                headers.put("User-Agent", USER_AGENT);
            }
            if (request.expectContinue()) {
                headers.put("Expect", "100-continue");
            }
        });
        request.bodyPublisher().ifPresent(publisher -> body(outbound, request.method(), publisher));
        InputStreamResponseListener listener = new InputStreamResponseListener();
        outbound.send(listener);
        try {
            Duration timeout = request.timeout().orElse(null);
            Response response = listener.get(timeout == null ? UNBOUNDED : timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Exchange(response, listener);
        } catch (TimeoutException timedOut) {
            HttpTimeoutException failure = new HttpTimeoutException("request timed out: " + request.uri());
            outbound.abort(failure);
            throw failure;
        } catch (InterruptedException interrupted) {
            outbound.abort(interrupted);
            throw interrupted;
        } catch (ExecutionException failed) {
            throw translated(failed.getCause(), request.uri());
        }
    }

    /** Hand the publisher's buffers to Jetty as they come, with the length it declares when it declares one. */
    private static void body(Request outbound, String method, HttpRequest.BodyPublisher publisher) {
        long length = publisher.contentLength();
        if (length == 0) {
            if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                outbound.body(new BytesRequestContent((String) null, new byte[0]));
            }
            return;
        }
        AsyncRequestContent content = new AsyncRequestContent((String) null) {
            @Override
            public long getLength() {
                return length;
            }
        };
        publisher.subscribe(new Upload(content));
        outbound.body(content);
    }

    /** Jetty's failure as the exception the JDK's client throws for it. */
    private IOException translated(Throwable cause, URI uri) {
        Throwable failure = cause;
        while (failure != null && !(failure instanceof IOException)) {
            failure = failure.getCause();
        }
        if (failure instanceof RebindingRefused || failure instanceof UnknownHostException
                || failure instanceof ConnectException || failure instanceof HttpTimeoutException) {
            return (IOException) failure;
        }
        if (failure instanceof SocketTimeoutException && connectTimeout != null) {
            HttpConnectTimeoutException timedOut = new HttpConnectTimeoutException("connect timed out: " + uri);
            timedOut.initCause(failure);
            return timedOut;
        }
        if (failure instanceof IOException io) {
            return io;
        }
        return new IOException("request to " + uri + " failed: " + cause, cause);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return Objects.equals(lower(left.getScheme()), lower(right.getScheme()))
                && Objects.equals(lower(left.getHost()), lower(right.getHost())) && port(left) == port(right);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    // ---- the exchange and its body ----

    /** A response whose headers have arrived and whose body is still to be read. */
    private record Exchange(Response response, InputStreamResponseListener listener) {

        /** Close the body unread: the response is a redirect the chain goes past. */
        void discard() throws IOException {
            listener.getInputStream().close();
        }

        /** Hand the body to the caller's handler and wait for the value it makes of it. */
        <T> HttpResponse<T> complete(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                     HttpResponse<T> previous) throws IOException, InterruptedException {
            HttpHeaders headers = headers(response);
            int status = response.getStatus();
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
            Download download = new Download(listener.getInputStream(), subscriber);
            subscriber.onSubscribe(download);
            Thread.ofVirtual().name("jenesis-http-body").start(download);
            T body;
            try {
                body = subscriber.getBody().toCompletableFuture().get();
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                throw cause instanceof IOException io ? io : new IOException(cause);
            }
            return new Received<>(status, request, Optional.ofNullable(previous), headers, body, request.uri());
        }

        static HttpHeaders headers(Response response) {
            Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (HttpField field : response.getHeaders()) {
                map.computeIfAbsent(field.getName(), _ -> new ArrayList<>()).add(field.getValue());
            }
            return HttpHeaders.of(map, (_, _) -> true);
        }
    }

    /** A response body read off Jetty's stream and handed to the caller's subscriber as it asks for it. */
    private static final class Download implements Flow.Subscription, Runnable {

        private final InputStream in;
        private final HttpResponse.BodySubscriber<?> subscriber;
        private final Object lock = new Object();
        private long demand;
        private boolean cancelled;

        Download(InputStream in, HttpResponse.BodySubscriber<?> subscriber) {
            this.in = in;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            synchronized (lock) {
                demand = n <= 0 || Long.MAX_VALUE - demand < n ? Long.MAX_VALUE : demand + n;
                lock.notifyAll();
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                cancelled = true;
                lock.notifyAll();
            }
            try {
                in.close();
            } catch (IOException _) {
                // closing an abandoned body is best effort; the connection is discarded either way
            }
        }

        @Override
        public void run() {
            try (in) {
                while (true) {
                    synchronized (lock) {
                        while (demand == 0 && !cancelled) {
                            lock.wait();
                        }
                        if (cancelled) {
                            return;
                        }
                        if (demand != Long.MAX_VALUE) {
                            demand--;
                        }
                    }
                    byte[] chunk = in.readNBytes(CHUNK);
                    if (chunk.length > 0) {
                        subscriber.onNext(List.of(ByteBuffer.wrap(chunk)));
                    }
                    if (chunk.length < CHUNK) {
                        subscriber.onComplete();
                        return;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                if (!cancelled) {
                    subscriber.onError(failure);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                subscriber.onError(interrupted);
            }
        }
    }

    /** A request body's buffers written to Jetty one at a time, the next asked for once Jetty has taken the last. */
    private static final class Upload implements Flow.Subscriber<ByteBuffer> {

        private final AsyncRequestContent content;
        private Flow.Subscription subscription;

        Upload(AsyncRequestContent content) {
            this.content = content;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(ByteBuffer buffer) {
            content.write(buffer, Callback.from(() -> subscription.request(1), _ -> subscription.cancel()));
        }

        @Override
        public void onError(Throwable failure) {
            content.fail(failure);
        }

        @Override
        public void onComplete() {
            content.close();
        }
    }

    /** The response the caller is answered with. */
    private record Received<T>(int statusCode, HttpRequest request, Optional<HttpResponse<T>> previousResponse,
                               HttpHeaders headers, T body, URI uri) implements HttpResponse<T> {

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    // ---- the Jetty client behind it ----

    /** One started Jetty client, shared by every {@link ScreenedHttpClient} built with the same settings. */
    private static final class Engine {

        private record Key(Duration connectTimeout, SSLContext sslContext, Resolver resolver) {
        }

        private final org.eclipse.jetty.client.HttpClient client;

        private Engine(Key key) {
            QueuedThreadPool threads = new QueuedThreadPool();
            threads.setName("jenesis-http");
            threads.setDaemon(true);
            threads.setMinThreads(2);
            ScheduledExecutorScheduler scheduler = new ScheduledExecutorScheduler("jenesis-http-scheduler", true);
            SslContextFactory.Client tls = new SslContextFactory.Client();
            tls.setSslContext(key.sslContext());
            tls.setEndpointIdentificationAlgorithm("HTTPS");
            ClientConnector connector = new ClientConnector();
            connector.setExecutor(threads);
            connector.setScheduler(scheduler);
            connector.setSelectors(1);
            connector.setSslContextFactory(tls);
            if (key.connectTimeout() != null) {
                connector.setConnectTimeout(key.connectTimeout());
            }
            client = new org.eclipse.jetty.client.HttpClient(new HttpClientTransportOverHTTP(connector));
            client.setExecutor(threads);
            client.setScheduler(scheduler);
            client.setFollowRedirects(false);
            client.setUserAgentField(null);
            client.setMaxRequestsQueuedPerDestination(16_384);
            // A body carries the Content-Type its caller names and none otherwise; Jetty would name one itself.
            client.setDefaultRequestContentType(null);
            client.setSocketAddressResolver(new Screened(threads, key.resolver()));
            try {
                client.start();
            } catch (Exception failure) {
                throw new IllegalStateException("the HTTP client could not start", failure);
            }
            // The decoders are discovered as the client starts, so they go after it: a body is relayed as it was sent,
            // and no Accept-Encoding is offered on a caller's behalf. And a response is the caller's to read as it
            // came: Jetty would answer a challenge, follow a redirect and refuse a 401 that names no challenge.
            client.getContentDecoderFactories().clear();
            client.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
            client.getProtocolHandlers().remove(ProxyAuthenticationProtocolHandler.NAME);
            client.getProtocolHandlers().remove(RedirectProtocolHandler.NAME);
        }

        static Engine of(Duration connectTimeout, SSLContext sslContext, Resolver resolver) {
            return ENGINES.computeIfAbsent(new Key(connectTimeout, sslContext, resolver), Engine::new);
        }
    }

    /** Resolution through {@link PrivateHosts#connectable}, so a host a screen admitted stays on public addresses. */
    private record Screened(Executor executor, Resolver resolver) implements SocketAddressResolver {

        @Override
        public void resolve(String host, int port, Map<String, Object> context,
                            Promise<List<InetSocketAddress>> promise) {
            executor.execute(() -> {
                try {
                    List<InetAddress> resolved = resolver.resolve(host);
                    List<InetAddress> connectable = PrivateHosts.connectable(host, resolved);
                    if (connectable.isEmpty() && !resolved.isEmpty()) {
                        promise.failed(new RebindingRefused(host));
                    } else if (connectable.isEmpty()) {
                        promise.failed(new UnknownHostException(host));
                    } else {
                        promise.succeeded(connectable.stream().map(address -> new InetSocketAddress(address, port))
                                .toList());
                    }
                } catch (Throwable failure) {
                    promise.failed(failure);
                }
            });
        }
    }

    // ---- building ----

    /** The JDK's builder surface, for the settings this client honours. */
    public static final class Builder implements HttpClient.Builder {

        private Duration connectTimeout;
        private Redirect redirect = Redirect.NEVER;
        private SSLContext sslContext;
        private Resolver resolver = Resolver.SYSTEM;

        private Builder() {
        }

        @Override
        public Builder cookieHandler(CookieHandler cookieHandler) {
            throw new UnsupportedOperationException("this client keeps no cookies");
        }

        @Override
        public Builder connectTimeout(Duration duration) {
            this.connectTimeout = Objects.requireNonNull(duration, "duration");
            return this;
        }

        @Override
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
            return this;
        }

        @Override
        public Builder sslParameters(SSLParameters sslParameters) {
            throw new UnsupportedOperationException("this client takes its TLS parameters from its SSLContext");
        }

        /** Accepted and unused: responses are handed on virtual threads of this client's own. */
        @Override
        public Builder executor(Executor executor) {
            Objects.requireNonNull(executor, "executor");
            return this;
        }

        @Override
        public Builder followRedirects(Redirect policy) {
            this.redirect = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Accepted and unused: this client speaks HTTP/1.1. */
        @Override
        public Builder version(Version version) {
            Objects.requireNonNull(version, "version");
            return this;
        }

        /** Accepted and unused: there is no HTTP/2 stream to prioritise. */
        @Override
        public Builder priority(int priority) {
            return this;
        }

        @Override
        public Builder proxy(ProxySelector proxySelector) {
            throw new UnsupportedOperationException("this client connects directly");
        }

        @Override
        public Builder authenticator(Authenticator authenticator) {
            throw new UnsupportedOperationException("this client sends the credentials its caller names");
        }

        /** Resolve host names through {@code resolver} instead of the system - the seam a test names a rebinding
         *  answer through. */
        public Builder resolver(Resolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        @Override
        public ScreenedHttpClient build() {
            SSLContext tls = sslContext;
            if (tls == null) {
                try {
                    tls = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException unavailable) {
                    throw new IllegalStateException("the default TLS context is unavailable", unavailable);
                }
            }
            return new ScreenedHttpClient(Engine.of(connectTimeout, tls, resolver), connectTimeout, redirect, tls);
        }
    }
}
