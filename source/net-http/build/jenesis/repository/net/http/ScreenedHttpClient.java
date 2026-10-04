package build.jenesis.repository.net.http;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.Origins;
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
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;

/**
 * A {@link HttpClient} whose connections Jetty's client makes: every outbound call goes through one, built by
 * {@link #newBuilder()} in place of {@code HttpClient.newBuilder()}, with the caller's JDK {@link HttpRequest},
 * {@link HttpResponse.BodyHandler} and {@link HttpRequest.BodyPublisher} bridged onto Jetty's.
 *
 * <p><b>What it changes about a call.</b>
 * <ul>
 *   <li>A host a private-address screen admitted as public is connected to only at a public address
 *       ({@link PrivateHosts#connectable}): a name that rebinds between screen and connect is refused, naming it. A
 *       host no screen admitted connects as it resolves.</li>
 *   <li>The request carries {@value #USER_AGENT} unless the caller names a {@code User-Agent}, and nothing about the
 *       runtime - no versions, no unasked {@code Accept-Encoding}, no {@code HTTP2-Settings} upgrade offer.</li>
 *   <li>A body is exchanged as sent: nothing is decompressed, so a proxy relays bytes and {@code Content-Encoding}
 *       together, and no {@code Content-Type} is added - a store signing its requests signs the headers it set.</li>
 *   <li>A response is the caller's to read: no challenge is answered, and a {@code 401} without one stays a
 *       {@code 401}.</li>
 *   <li>{@link HttpClient.Redirect#NORMAL} follows as the JDK does - never {@code https} to {@code http} - and drops
 *       {@code Authorization}, {@code Cookie} and the repository key header when a hop leaves their origin.</li>
 *   <li>A redirect leaving the call's origin for a host resolving to a private, loopback or link-local address
 *       ({@link PrivateHosts#resolvesToPrivate}) is refused with {@link RedirectRefused} unless the builder
 *       {@linkplain Builder#redirectsToPrivateHosts admits it}: the peer chooses where a redirect leads, and could
 *       otherwise send the call to a metadata service. A hop within the origin passes, and so does one from a private
 *       origin to another private host when the builder
 *       {@linkplain Builder#redirectsWithinPrivateNetwork says so}.</li>
 * </ul>
 *
 * <p><b>No call waits without a bound.</b> A connect gives up after {@link #CONNECT_TIMEOUT} unless the builder names
 * another ({@link HttpConnectTimeoutException}). An exchange gives up after {@link #IDLE_TIMEOUT} with nothing sent or
 * received ({@link Builder#idleTimeout}, or the request's {@linkplain HttpRequest#timeout() timeout} when longer),
 * answering {@link HttpTimeoutException} naming the address - a bound on silence, so a transfer that keeps moving is
 * never cut short. A peer answering a byte at a time defeats silence, so a download must also move
 * {@link #THROUGHPUT_FLOOR} bytes per {@link #FLOOR_WINDOW} ({@link Builder#throughputFloor}). Only a whole-call bound
 * ends a peer trickling just above the floor: {@link Builder#deadline} names one, from request to last byte across
 * redirects, and there is none unless named, since any fixed number either cuts a legitimate slow transfer or protects
 * nothing.
 *
 * <p>Otherwise the JDK's contract holds: {@link HttpRequest#timeout()} bounds the wait for headers, and a handler's
 * body streams. It speaks HTTP/1.1, over TLS where the URL says so, verifying the host against the default trust or the
 * builder's {@link SSLContext}. A cookie handler, authenticator or proxy selector is refused at build time, since a
 * silently ignored security setting would not hold.
 *
 * <p>Clients built with the same connect timeout, trust and resolver share one Jetty client and pool, so
 * {@link #close()} releases nothing and a caller need not hold a client to avoid a leak. A composition holds a
 * {@link #lease()} for its life and closes it as it shuts down; once the last lease is closed the shared clients are
 * stopped, their threads with them, and a client built after starts afresh. A process holding no lease - a command-line
 * tool - keeps them until it exits, on daemon threads that never hold it open.
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

    /** How long a connect is waited for when the builder names no {@linkplain Builder#connectTimeout timeout}. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long an exchange may go with nothing sent or received - waiting for headers once sent, or for either body's
     *  next bytes - unless the builder names {@linkplain Builder#idleTimeout another}. */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(1);

    /** The least a response body must move per {@link #FLOOR_WINDOW}, in bytes, unless the builder names
     *  {@linkplain Builder#throughputFloor another}: sixteen kibibytes a minute, far below any link worth finishing a
     *  download over and far above a byte-at-a-time peer. Text, since a settings catalogue publishes it as a
     *  default. */
    public static final String THROUGHPUT_FLOOR_TEXT = "16384";

    /** {@link #THROUGHPUT_FLOOR_TEXT} as the number the client applies. */
    public static final long THROUGHPUT_FLOOR = Long.parseLong(THROUGHPUT_FLOOR_TEXT);

    /** The span of reading a {@linkplain #THROUGHPUT_FLOOR throughput floor} is measured over. */
    public static final Duration FLOOR_WINDOW = Duration.ofMinutes(1);

    /** The whole-call deadline unless the builder names {@linkplain Builder#deadline another}: none, as the zero
     *  duration a settings catalogue publishes. */
    public static final String DEADLINE_TEXT = "PT0S";

    private static final Map<Engine.Key, Engine> ENGINES = new ConcurrentHashMap<>();

    /** The leases open on the shared clients; guarded by {@link #ENGINES}. */
    private static int leases;

    private final Engine engine;
    private final Duration connectTimeout;
    private final Duration idleTimeout;
    private final Floor floor;
    private final Supplier<Duration> deadline;
    private final Redirect redirect;
    private final BooleanSupplier privateRedirects;
    private final boolean withinPrivateNetwork;
    private final SSLContext sslContext;

    private ScreenedHttpClient(Engine engine, Duration connectTimeout, Duration idleTimeout, Floor floor,
                               Supplier<Duration> deadline, Redirect redirect, BooleanSupplier privateRedirects,
                               boolean withinPrivateNetwork, SSLContext sslContext) {
        this.engine = engine;
        this.connectTimeout = connectTimeout;
        this.idleTimeout = idleTimeout;
        this.floor = floor;
        this.deadline = deadline;
        this.redirect = redirect;
        this.privateRedirects = privateRedirects;
        this.withinPrivateNetwork = withinPrivateNetwork;
        this.sslContext = sslContext;
    }

    /** The least a body must move per window of reading; the bytes are read afresh per exchange so a live setting is
     *  honoured, and {@code 0} lifts the floor. */
    private record Floor(LongSupplier bytes, Duration window) {
    }

    /**
     * A hold on the shared clients for as long as the returned lease is open: a composition takes one as it starts and
     * closes it as it shuts down, and closing the last one stops every shared client and its threads.
     */
    public static Lease lease() {
        synchronized (ENGINES) {
            leases++;
        }
        return new Lease();
    }

    /** A composition's hold on the shared clients; closing it twice counts once. */
    public static final class Lease implements AutoCloseable {

        private final AtomicBoolean open = new AtomicBoolean(true);

        private Lease() {
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            synchronized (ENGINES) {
                if (--leases == 0) {
                    ENGINES.values().forEach(Engine::stop);
                    ENGINES.clear();
                }
            }
        }
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

    /** When one call must be done by, read as it starts: {@code total} from sending, or {@link #NONE}. */
    private record Deadline(Duration total, long due) {

        static final Deadline NONE = new Deadline(Duration.ZERO, 0);

        static Deadline of(Duration total) {
            return total == null || total.isZero() || total.isNegative() ? NONE
                    : new Deadline(total, System.nanoTime() + total.toNanos());
        }

        boolean set() {
            return !total.isZero();
        }

        long remaining() {
            return due - System.nanoTime();
        }

        HttpTimeoutException passed(URI uri) {
            return new HttpTimeoutException("the call to " + uri + " did not complete within its deadline of "
                    + total.toMillis() + " ms, so it was abandoned (deadline)");
        }
    }

    /** A followed redirect refused for leaving the call's origin for a private, loopback or link-local host. */
    public static final class RedirectRefused extends IOException {

        RedirectRefused(URI from, URI to) {
            super("refusing to follow the redirect from " + from + " to " + to + ": it leaves the origin the call was "
                    + "made to for a private, loopback or link-local address, which no screen admitted");
        }
    }

    /** A connection refused because the host now resolves only to addresses the admitting screen refused. */
    public static final class RebindingRefused extends IOException {

        RebindingRefused(String host) {
            super("refusing to connect to " + host + ": a private-address screen admitted it as public, and it now "
                    + "resolves only to private, loopback or link-local addresses (DNS rebinding)");
        }
    }

    // ---- the JDK's accessors

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.of(connectTimeout);
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

    // ---- sending

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        URI origin = request.uri();
        Deadline due = Deadline.of(deadline.get());
        HttpRequest current = request;
        HttpResponse<T> previous = null;
        for (int hop = 0; ; hop++) {
            Exchange exchange = exchange(current, due);
            Optional<HttpRequest> next;
            try {
                next = hop < MAX_REDIRECTS ? redirected(origin, current, exchange) : Optional.empty();
            } catch (RedirectRefused refused) {
                exchange.discard();
                throw refused;
            }
            if (next.isEmpty()) {
                return exchange.complete(current, handler, previous);
            }
            exchange.discard();
            // The JDK hands a followed redirect back as a body-less previous response, and so does this.
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

    /** The request a followed redirect sends next, or empty when this response is the one to answer with.
     *
     * @throws RedirectRefused when the redirect leaves {@code origin} for a private host this client does not admit
     */
    private Optional<HttpRequest> redirected(URI origin, HttpRequest request, Exchange exchange)
            throws RedirectRefused {
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
        boolean sameOrigin = Origins.same(origin, target);
        if (!sameOrigin && !privateRedirects.getAsBoolean() && PrivateHosts.resolvesToPrivate(target.getHost())
                && !(withinPrivateNetwork && PrivateHosts.resolvesToPrivate(origin.getHost()))) {
            throw new RedirectRefused(request.uri(), target);
        }
        boolean keepsBody = status == 307 || status == 308;
        String method = keepsBody || request.method().equals("HEAD") ? request.method() : "GET";
        HttpRequest.Builder next = HttpRequest.newBuilder(target)
                .method(method, keepsBody ? request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())
                        : HttpRequest.BodyPublishers.noBody())
                .expectContinue(request.expectContinue());
        request.timeout().ifPresent(next::timeout);
        request.headers().map().forEach((name, values) -> {
            if (sameOrigin || !SENSITIVE.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> next.header(name, value));
            }
        });
        return Optional.of(next.build());
    }

    /** Send one request and wait for its headers, the body left to stream - all before {@code due} when a deadline is
     *  set, or the exchange is aborted naming it. */
    private Exchange exchange(HttpRequest request, Deadline due) throws IOException, InterruptedException {
        // A request naming a longer wait for its headers than the idle timeout is waited for that long.
        Duration idle = request.timeout().filter(named -> named.compareTo(idleTimeout) > 0).orElse(idleTimeout);
        Request outbound = engine.client.newRequest(request.uri())
                .method(request.method())
                .idleTimeout(idle.toMillis(), TimeUnit.MILLISECONDS)
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
        Runnable disarm = () -> { };
        if (due.set()) {
            long remaining = due.remaining();
            if (remaining <= 0) {
                throw due.passed(request.uri());
            }
            // The abort fails whatever waits on the exchange - headers or body - with the deadline's own exception.
            Scheduler.Task task = engine.client.getScheduler().schedule(
                    () -> outbound.abort(due.passed(request.uri())), remaining, TimeUnit.NANOSECONDS);
            disarm = task::cancel;
        }
        outbound.send(listener);
        try {
            // With no timeout named, the idle timeout ends a wait for headers that are not coming.
            Response response = request.timeout().isPresent()
                    ? listener.get(request.timeout().get().toMillis(), TimeUnit.MILLISECONDS)
                    : listener.get(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            return new Exchange(response, listener, request.uri(), idle, floor.bytes().getAsLong(), floor.window(),
                    disarm);
        } catch (TimeoutException timedOut) {
            disarm.run();
            HttpTimeoutException failure = new HttpTimeoutException("request timed out: " + request.uri());
            outbound.abort(failure);
            throw failure;
        } catch (InterruptedException interrupted) {
            disarm.run();
            outbound.abort(interrupted);
            throw interrupted;
        } catch (ExecutionException failed) {
            disarm.run();
            throw translated(failed.getCause(), request.uri(), idle);
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
    private IOException translated(Throwable cause, URI uri, Duration idle) {
        HttpTimeoutException stalled = stalled(cause, uri, idle);
        if (stalled != null) {
            return stalled;
        }
        Throwable failure = cause;
        while (failure != null && !(failure instanceof IOException)) {
            failure = failure.getCause();
        }
        if (failure instanceof RebindingRefused || failure instanceof UnknownHostException
                || failure instanceof ConnectException || failure instanceof HttpTimeoutException) {
            return (IOException) failure;
        }
        if (failure instanceof SocketTimeoutException) {
            HttpConnectTimeoutException timedOut = new HttpConnectTimeoutException("connect timed out: " + uri);
            timedOut.initCause(failure);
            return timedOut;
        }
        if (failure instanceof IOException io) {
            return io;
        }
        return new IOException("request to " + uri + " failed: " + cause, cause);
    }

    /** The idle timeout Jetty ended an exchange with, as the JDK client's timeout, or {@code null} for any other
     *  failure. A connect timeout is a {@link SocketTimeoutException} instead. */
    private static HttpTimeoutException stalled(Throwable cause, URI uri, Duration idle) {
        for (Throwable failure = cause; failure != null; failure = failure.getCause()) {
            if (failure instanceof HttpTimeoutException timedOut) {
                return timedOut;
            }
            if (failure instanceof TimeoutException) {
                HttpTimeoutException timedOut = new HttpTimeoutException("no answer from " + uri.getHost()
                        + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + " for " + idle.toMillis()
                        + " ms, so the call to " + uri + " was abandoned (idle timeout)");
                timedOut.initCause(cause);
                return timedOut;
            }
        }
        return null;
    }

    // ---- the exchange and its body

    /** A response whose headers have arrived and whose body is still to be read. */
    private record Exchange(Response response, InputStreamResponseListener listener, URI uri, Duration idle,
                            long floor, Duration window, Runnable disarm) {

        /** Close the body unread: a redirect the chain passes, or one it refuses. */
        void discard() throws IOException {
            disarm.run();
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
            Download download = new Download(listener.getInputStream(), subscriber, uri, idle, floor, window, disarm);
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

    /** A response body read off Jetty's stream and handed to the caller's subscriber on demand, in whatever pieces
     *  arrive, so the throughput floor is judged as bytes come. Only time waiting on the peer counts towards a window;
     *  a caller slow to ask for more is applying backpressure. */
    private static final class Download implements Flow.Subscription, Runnable {

        private final InputStream in;
        private final HttpResponse.BodySubscriber<?> subscriber;
        private final URI uri;
        private final Duration idle;
        private final long floor;
        private final long window;
        private final Runnable disarm;
        private final Object lock = new Object();
        private long demand;
        private boolean cancelled;

        Download(InputStream in, HttpResponse.BodySubscriber<?> subscriber, URI uri, Duration idle, long floor,
                 Duration window, Runnable disarm) {
            this.in = in;
            this.subscriber = subscriber;
            this.uri = uri;
            this.idle = idle;
            this.floor = floor;
            this.window = window.toNanos();
            this.disarm = disarm;
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
                // best effort; the connection is discarded either way
            }
        }

        @Override
        public void run() {
            byte[] buffer = new byte[CHUNK];
            long waited = 0;
            long moved = 0;
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
                    long started = System.nanoTime();
                    int read = in.read(buffer, 0, CHUNK);
                    if (read < 0) {
                        subscriber.onComplete();
                        return;
                    }
                    waited += System.nanoTime() - started;
                    moved += read;
                    if (waited >= window) {
                        if (moved < floor) {
                            throw new HttpTimeoutException(uri + " moved " + moved + " bytes in "
                                    + Duration.ofNanos(waited).toSeconds() + " s of reading, below the throughput "
                                    + "floor of " + floor + " bytes a " + Duration.ofNanos(window).toSeconds()
                                    + " s, so the call was abandoned");
                        }
                        waited = 0;
                        moved = 0;
                    }
                    subscriber.onNext(List.of(ByteBuffer.wrap(Arrays.copyOf(buffer, read))));
                }
            } catch (IOException | RuntimeException failure) {
                if (!cancelled) {
                    HttpTimeoutException stalled = stalled(failure, uri, idle);
                    subscriber.onError(stalled == null ? failure : stalled);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                subscriber.onError(interrupted);
            } finally {
                disarm.run();
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

    // ---- the Jetty client behind it

    /** One started Jetty client, shared by every {@link ScreenedHttpClient} built with the same settings. */
    private static final class Engine {

        private record Key(Duration connectTimeout, SSLContext sslContext, Resolver resolver) {
        }

        private final org.eclipse.jetty.client.HttpClient client;
        private final QueuedThreadPool threads;
        private final ScheduledExecutorScheduler scheduler;

        /** Stop the client and its threads; a request still in flight on it fails as a closed connection does. */
        void stop() {
            for (org.eclipse.jetty.util.component.LifeCycle part : List.of(client, scheduler, threads)) {
                try {
                    part.stop();
                } catch (Exception ignored) {
                    // Stopping is best-effort: what does not stop holds only daemon threads.
                }
            }
        }

        private Engine(Key key) {
            threads = new QueuedThreadPool();
            threads.setName("jenesis-http");
            threads.setDaemon(true);
            threads.setMinThreads(2);
            scheduler = new ScheduledExecutorScheduler("jenesis-http-scheduler", true);
            SslContextFactory.Client tls = new SslContextFactory.Client();
            tls.setSslContext(key.sslContext());
            tls.setEndpointIdentificationAlgorithm("HTTPS");
            ClientConnector connector = new ClientConnector();
            connector.setExecutor(threads);
            connector.setScheduler(scheduler);
            connector.setSelectors(1);
            connector.setSslContextFactory(tls);
            connector.setConnectTimeout(key.connectTimeout());
            client = new org.eclipse.jetty.client.HttpClient(new HttpClientTransportOverHTTP(connector));
            client.setExecutor(threads);
            client.setScheduler(scheduler);
            client.setFollowRedirects(false);
            client.setUserAgentField(null);
            client.setMaxRequestsQueuedPerDestination(16_384);
            // A body carries the Content-Type its caller names and none otherwise.
            client.setDefaultRequestContentType(null);
            client.setSocketAddressResolver(new Screened(threads, key.resolver()));
            try {
                client.start();
            } catch (Exception failure) {
                throw new IllegalStateException("the HTTP client could not start", failure);
            }
            // The decoders are discovered as the client starts, so they are cleared after it: bodies relay as sent and
            // no Accept-Encoding is offered. Jetty would also answer challenges, follow redirects and refuse a bare
            // 401.
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

    // ---- building

    /** The JDK's builder surface, for the settings this client honours. */
    public static final class Builder implements HttpClient.Builder {

        private Duration connectTimeout = CONNECT_TIMEOUT;
        private Duration idleTimeout = IDLE_TIMEOUT;
        private Floor floor = new Floor(() -> THROUGHPUT_FLOOR, FLOOR_WINDOW);
        private Supplier<Duration> deadline = () -> Duration.ZERO;
        private Redirect redirect = Redirect.NEVER;
        private BooleanSupplier privateRedirects = () -> false;
        private boolean withinPrivateNetwork;
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

        /** How long an exchange may go with nothing sent or received before an {@link HttpTimeoutException};
         *  {@link #IDLE_TIMEOUT} unless named. A caller whose peer thinks longer before answering - a storage service
         *  assembling a large object - names a longer one. */
        public Builder idleTimeout(Duration duration) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("an idle timeout is positive: " + duration);
            }
            this.idleTimeout = duration;
            return this;
        }

        /** The least a response body must move over {@code window} before the call is abandoned with an
         *  {@link HttpTimeoutException}; {@link #THROUGHPUT_FLOOR} over {@link #FLOOR_WINDOW} unless named. The bytes
         *  are read per exchange, so a live setting works; {@code 0} lifts the floor. */
        public Builder throughputFloor(LongSupplier bytes, Duration window) {
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("a throughput window is positive: " + window);
            }
            this.floor = new Floor(Objects.requireNonNull(bytes, "bytes"), window);
            return this;
        }

        /** The longest one call may take, from sending to its body's last byte across every redirect, before an
         *  {@link HttpTimeoutException} naming the deadline; none unless named. It ends a peer trickling just above the
         *  throughput floor, at the price of cutting a longer legitimate transfer, so the number is the caller's. Read
         *  as each call starts; a zero or negative duration sets none. */
        public Builder deadline(Supplier<Duration> deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /** Whether a followed redirect may leave the call's origin for a private, loopback or link-local host; not
         *  unless named. A caller whose deployment admits internal targets hands that dial here, read per redirect. */
        public Builder redirectsToPrivateHosts(BooleanSupplier admitted) {
            this.privateRedirects = Objects.requireNonNull(admitted, "admitted");
            return this;
        }

        /** The calls this client makes go to operator-configured hosts - an identity provider, a key server, a trust
         *  root - so a call to a host resolving privately may follow a redirect to another private host (an internal
         *  IdP behind a load balancer). A call to a public host is still refused a private target. */
        public Builder redirectsWithinPrivateNetwork() {
            this.withinPrivateNetwork = true;
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

        /** Resolve host names through {@code resolver} instead of the system - a test's seam for a rebinding answer. */
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
            return new ScreenedHttpClient(Engine.of(connectTimeout, tls, resolver), connectTimeout, idleTimeout, floor,
                    deadline, redirect, privateRedirects, withinPrivateNetwork, tls);
        }
    }
}
