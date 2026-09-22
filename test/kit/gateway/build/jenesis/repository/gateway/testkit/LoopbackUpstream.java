package build.jenesis.repository.gateway.testkit;

import module java.base;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * A hermetic upstream on a real loopback socket, shared by the format proxy tests. It stands in for the one wire the
 * proxy stack never faked otherwise: the upstream FETCH side, exercised end to end through the real
 * {@code build.jenesis.repository.proxy.HttpFetcher} instead of an in-memory {@code Fetcher} map. A test registers
 * recorded responses by request path and points a booted server (or a fetcher) at {@link #base()}; the server
 * pull-through-proxies over a socket, so revalidation, negative caching and streaming all run for real.
 *
 * <p>WireMock-backed throughout. The plain stub/counter duties - a fixed body for a path, a non-disclosive {@code 404}
 * for an unregistered one, per-server/per-path request counts - sit alongside the misbehaviour matrix
 * {@code HttpFetcherWireTest} drives, all expressed with WireMock: conditional {@code ETag}/{@code If-None-Match} →
 * {@code 304}, {@code Range} → {@code 206}/{@code 416} (a response transformer that slices the recorded body),
 * redirects and a bounded redirect loop, a transient {@code 5xx} that recovers on retry (a Scenario), a truncated body
 * ({@link Fault#MALFORMED_RESPONSE_CHUNK} - the upstream sends an incomplete body the buffered read and the streamed
 * copy both reject with an {@link java.io.IOException}, the fail-closed contract, though the exact wire is a corrupt
 * chunk rather than a short {@code Content-Length}), and a stall ({@code withFixedDelay} past the client's request
 * timeout, so it is clipped into the contract's transport failure). {@code HEAD} needs no workaround - the WireMock
 * server answers a {@code HEAD} with the size header and no body natively.
 */
public final class LoopbackUpstream implements AutoCloseable {

    private final WireMockServer server;
    private final Map<String, RangeBody> rangeBodies;

    private LoopbackUpstream(WireMockServer server, Map<String, RangeBody> rangeBodies) {
        this.server = server;
        this.rangeBodies = rangeBodies;
    }

    public static LoopbackUpstream start() {
        Map<String, RangeBody> rangeBodies = new ConcurrentHashMap<>();
        WireMockServer server = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("localhost")
                .dynamicPort()
                .extensions(new RangeTransformer(rangeBodies)));
        server.start();
        // An unregistered path answers a non-disclosive 404 with an empty body, matching the hand-rolled default. It
        // sits at the lowest precedence (highest priority number) so any registered stub wins.
        server.stubFor(any(anyUrl()).atPriority(Integer.MAX_VALUE).willReturn(aResponse().withStatus(404)));
        return new LoopbackUpstream(server, rangeBodies);
    }

    /** The loopback root; append a path or hand it to {@code RepositoryApplication.start(port, upstreams, null)}. */
    public URI base() {
        return URI.create("http://localhost:" + server.port() + "/");
    }

    /** The absolute loopback URL of a registered path (for embedding in a proxied index's rewritten links). */
    public String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    public int total() {
        return server.getAllServeEvents().size();
    }

    public int hits(String path) {
        return (int) server.getAllServeEvents().stream()
                .filter(event -> path.equals(requestPath(event)))
                .count();
    }

    // ---- registration --------------------------------------------------------------------------------------------

    /** Serve a fixed body with a content type: the common recorded-bytes case. */
    public LoopbackUpstream serve(String path, int status, String contentType, byte[] body) {
        return serve(path, status, Map.of("Content-Type", contentType), body);
    }

    /** Serve a fixed body with explicit headers. An explicit {@code Content-Length} is set so a {@code HEAD} answers
     *  the size the gateway's HEAD path copies through (Jetty otherwise reports no length on a bodyless HEAD - the
     *  reason the hand-rolled double carried its own HEAD Content-Length workaround). */
    public LoopbackUpstream serve(String path, int status, Map<String, String> headers, byte[] body) {
        ResponseDefinitionBuilder response = aResponse().withStatus(status).withBody(body);
        headers.forEach(response::withHeader);
        if (body.length > 0 && !headers.containsKey("Content-Length")) {
            response.withHeader("Content-Length", Integer.toString(body.length));
        }
        // Match on the path only (query ignored), as the hand-rolled dispatch keyed on the raw path. A later stub for
        // the same path takes precedence, mirroring the map's replace-on-re-register semantics.
        server.stubFor(any(urlPathEqualTo(path)).willReturn(response));
        return this;
    }

    /** Serve a body validated by an {@code ETag}: a matching {@code If-None-Match} is answered {@code 304}. */
    public LoopbackUpstream serveEtag(String path, String etag, String contentType, byte[] body) {
        server.stubFor(any(urlPathEqualTo(path)).atPriority(1)
                .withHeader("If-None-Match", equalTo(etag))
                .willReturn(aResponse().withStatus(304).withHeader("ETag", etag)));
        server.stubFor(any(urlPathEqualTo(path)).atPriority(2)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", contentType).withHeader("ETag", etag).withBody(body)));
        return this;
    }

    /** Serve a body that honours a {@code Range} request ({@code 206} with a {@code Content-Range}, {@code 416} past
     *  the end); the {@link RangeTransformer} slices the recorded body per request. */
    public LoopbackUpstream serveRange(String path, String contentType, byte[] body) {
        rangeBodies.put(path, new RangeBody(body, contentType));
        server.stubFor(any(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200).withTransformers("range")));
        return this;
    }

    /** Redirect this path to a location with the given status ({@code 302}); a two-node cycle proves the client bounds it. */
    public LoopbackUpstream redirect(String path, String location, int status) {
        server.stubFor(any(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(status).withHeader("Location", location)));
        return this;
    }

    /** Fail with {@code failStatus} the first {@code failures} times, then serve the body: a transient upstream that
     *  recovers, modelled as a WireMock Scenario advancing one state per failing attempt. */
    public LoopbackUpstream flaky(String path, int failures, int failStatus, String contentType, byte[] body) {
        String scenario = "flaky:" + path;
        String state = Scenario.STARTED;
        for (int attempt = 1; attempt <= failures; attempt++) {
            String next = scenario + ":" + attempt;
            server.stubFor(any(urlPathEqualTo(path)).inScenario(scenario)
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(failStatus))
                    .willSetStateTo(next));
            state = next;
        }
        server.stubFor(any(urlPathEqualTo(path)).inScenario(scenario)
                .whenScenarioStateIs(state)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body)));
        return this;
    }

    /** Send an incomplete body and close: a truncated response a client must reject. WireMock cannot fake a body
     *  shorter than a declared {@code Content-Length} (Jetty sets it from the actual bytes), so this uses the closest
     *  wire fault - a malformed chunk then close - which the fetcher's buffered read and streamed copy both surface as
     *  an {@code IOException}, the same fail-closed contract. {@code declaredLength}/{@code actual}/{@code contentType}
     *  are retained for call-site parity. */
    public LoopbackUpstream truncated(String path, int declaredLength, byte[] actual, String contentType) {
        server.stubFor(any(urlPathEqualTo(path)).willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
        return this;
    }

    /** Accept the connection and stall well past any test's request timeout: the stalled upstream a request timeout
     *  must clip into the contract's transport failure. */
    public LoopbackUpstream stall(String path) {
        server.stubFor(any(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200).withFixedDelay((int) Duration.ofMinutes(5).toMillis())));
        return this;
    }

    // ---- plumbing ------------------------------------------------------------------------------------------------

    private static String requestPath(ServeEvent event) {
        return requestPath(event.getRequest().getUrl());
    }

    private static String requestPath(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    @Override
    public void close() {
        server.stop();
    }

    private record RangeBody(byte[] body, String contentType) {
    }

    /** Slices the recorded body for a {@code Range} request: {@code 206} with a {@code Content-Range} for a satisfiable
     *  window, {@code 416} past the end, a plain {@code 200} when no {@code Range} header is present. Named "range" and
     *  applied only to responses that opt in via {@code withTransformers("range")}. */
    private static final class RangeTransformer implements ResponseDefinitionTransformerV2 {

        private final Map<String, RangeBody> bodies;

        private RangeTransformer(Map<String, RangeBody> bodies) {
            this.bodies = bodies;
        }

        @Override
        public String getName() {
            return "range";
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            RangeBody recorded = bodies.get(requestPath(event.getRequest().getUrl()));
            byte[] body = recorded.body();
            String contentType = recorded.contentType();
            String range = event.getRequest().getHeader("Range");
            if (range == null || !range.startsWith("bytes=")) {
                return aResponse().withStatus(200)
                        .withHeader("Content-Type", contentType).withHeader("Accept-Ranges", "bytes")
                        .withBody(body).build();
            }
            String[] bounds = range.substring("bytes=".length()).split("-", 2);
            int start = bounds[0].isEmpty() ? 0 : Integer.parseInt(bounds[0]);
            if (start >= body.length) {
                return aResponse().withStatus(416)
                        .withHeader("Content-Range", "bytes */" + body.length).build();
            }
            int end = bounds.length > 1 && !bounds[1].isEmpty() ? Integer.parseInt(bounds[1]) : body.length - 1;
            end = Math.min(end, body.length - 1);
            return aResponse().withStatus(206)
                    .withHeader("Content-Type", contentType)
                    .withHeader("Content-Range", "bytes " + start + "-" + end + "/" + body.length)
                    .withBody(Arrays.copyOfRange(body, start, end + 1)).build();
        }
    }
}
