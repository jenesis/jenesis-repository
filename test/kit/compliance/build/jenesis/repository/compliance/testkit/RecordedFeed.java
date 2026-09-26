package build.jenesis.repository.compliance.testkit;

import module java.base;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A vendor endpoint, played back. It is an HTTP server bound to the <em>loopback address</em> that answers whatever
 * the current script says and records every request it was asked, so a signal source can be driven through the very
 * production code path a deployment uses - its own URL shaping, credential header, status branch, byte cap and
 * pagination cursor - against a recorded payload instead of the vendor.
 *
 * <h2>Why a server rather than the feeds' {@code Endpoint} seams</h2>
 * Every feed isolates its one network call behind a functional seam, and every seam-injected unit test therefore
 * <em>bypasses</em> exactly the code the contract is about: the non-200 branch, the cursor's origin guard, the request
 * the vendor's vocabulary was written into. {@code AdvisoryFeedFailClosedTest} already made that point for the status
 * branch. A source built by its own {@code SignalSourceProvider} from a configuration pointing here has no seam in it
 * at all - which is also what makes the contract's answers about the instance a gate actually reaches.
 *
 * <h2>Why the loopback address and not {@code localhost}</h2>
 * The base URI carries the loopback <em>literal</em> ({@code http://127.0.0.1:<port>}), so playing a recording back
 * costs no name resolution whatsoever. Under {@link NoEgressResolver} that is what makes the tripwire's record
 * unambiguous: any host in it is a reach for a vendor, never the harness talking to itself.
 *
 * <p>A script is a plain function from a recorded {@link Request} to a {@link Reply}, so a feed's own pagination shape
 * - a JSON cursor token, an RFC 5988 {@code Link} header, a relative {@code links.next} - stays with the fixture that
 * knows it, and the kit stays free of vendor knowledge.
 */
public final class RecordedFeed implements AutoCloseable {

    /** One request the feed made, as the vendor would have seen it. */
    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public Request {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            query = query == null ? "" : query;
            headers = Map.copyOf(headers);
            body = body == null ? "" : body;
        }

        /** The first value of a header, matched case-insensitively; {@code null} when the request carried none. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        /** Path, query and body as one percent-decoded string - what a fixture matches its vendor's spelling of an
         *  ecosystem against, whether that vendor writes it into the path (a purl segment), the query string
         *  ({@code ecosystem=pip}) or a posted JSON body. Decoding is what lets the expectation be written the way a
         *  reader thinks of it ({@code pkg:maven/...}) rather than the way a URL encoder wrote it. */
        public String target() {
            String raw = path + (query.isEmpty() ? "" : "?" + query) + (body.isEmpty() ? "" : " " + body);
            try {
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException _) {
                return raw;                             // a body that is not form-encoded; match it verbatim
            }
        }
    }

    /** One answer the recording plays back. */
    public record Reply(int status, String body, Map<String, String> headers) {

        public Reply {
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(headers);
        }

        /** A 200 carrying a recorded payload. */
        public static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }

        /** A rejection carrying whatever body the vendor really answers with. A fixture deliberately hands the
         *  <em>good</em> payload here: if a feed's status branch were dropped, the recording would parse and the
         *  contract's fail-mode check would see real data where it demands a refusal or the neutral answer. */
        public static Reply status(int status, String body) {
            return new Reply(status, body, Map.of());
        }

        /** The same reply carrying one more response header - a pagination {@code Link}, a {@code Retry-After}. */
        public Reply header(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, body, merged);
        }
    }

    /** A recorded endpoint's script: what this vendor answers to a given request. */
    @FunctionalInterface
    public interface Responder {
        Reply respond(Request request) throws IOException;
    }

    /** The most a recorded request body may carry. A feed's query is a small JSON document; this is a bound, not
     *  a budget, and a request past it is refused rather than buffered. */
    private static final int MAX_REQUEST_BODY = 64 * 1024;

    private final HttpServer server;
    private final URI base;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> responder;
    private final AtomicInteger endpoints = new AtomicInteger();

    private RecordedFeed(HttpServer server, URI base, Responder responder) {
        this.server = server;
        this.base = base;
        this.responder = new AtomicReference<>(responder);
    }

    /** Start a recorded endpoint on an ephemeral loopback port, initially playing {@code responder}. */
    public static RecordedFeed serving(Responder responder) throws IOException {
        Objects.requireNonNull(responder, "responder");
        InetAddress loopback = InetAddress.getLoopbackAddress();
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        String host = loopback.getHostAddress();
        URI base = URI.create("http://" + (host.contains(":") ? "[" + host + "]" : host) + ":"
                + server.getAddress().getPort());
        RecordedFeed feed = new RecordedFeed(server, base, responder);
        server.createContext("/", feed::handle);
        server.start();
        return feed;
    }

    /**
     * A base URI nothing else has used, for a source that must not share state with a previously built one. It is the
     * same server on a distinct path, which matters because a provider may legitimately memoize its source <em>per
     * endpoint</em> (the CISA catalogue does): a contract check that needs a cold source asks for a fresh endpoint
     * rather than silently re-testing the warm one the last check left behind.
     */
    public URI endpoint() {
        return base.resolve("/recorded-" + endpoints.incrementAndGet());
    }

    /** The base of the recorded endpoint - the loopback literal, so reaching it resolves no name. */
    public URI base() {
        return base;
    }

    /** Play a different script from here on. */
    public void answer(Responder responder) {
        this.responder.set(Objects.requireNonNull(responder, "responder"));
    }

    /** Every request the feed has received, oldest first. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The requests received since {@code before} was taken - what one contract check actually spent. */
    public List<Request> since(List<Request> before) {
        List<Request> now = requests();
        return now.size() <= before.size() ? List.of() : List.copyOf(now.subList(before.size(), now.size()));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // A bounded read, not a slurp (§1): what arrives here is a feed's own query body - a JSON
            // document of a few hundred bytes - and reading it whole is what lets a fixture assert the vendor's
            // spelling of an ecosystem reached the wire. Past the cap the recording refuses rather than buffering,
            // because a request body that large means this harness is being used for something it is not.
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY + 1);
            if (body.length > MAX_REQUEST_BODY) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(),
                    new String(body, StandardCharsets.UTF_8));
            requests.add(request);
            Reply reply;
            try {
                reply = responder.get().respond(request);
            } catch (IOException | RuntimeException e) {
                reply = new Reply(500, "the recorded script failed: " + e, Map.of());
            }
            byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach(exchange.getResponseHeaders()::set);
            if (reply.headers().keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(reply.status(), payload.length == 0 ? -1 : payload.length);
            exchange.getResponseBody().write(payload);
        }
    }
}
