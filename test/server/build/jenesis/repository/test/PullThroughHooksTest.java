package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link PullThroughHooks} serve seam - the proxy-leg twin of {@link build.jenesis.repository.server.EdgeHooks}.
 * Drives {@link PullThroughCache#serve} directly over a spy {@link ProxyFormat} and a spy hooks, proving: the free
 * {@link PullThroughHooks#NONE} default serves a local hit byte-for-byte as before with no upstream touch; a spy hooks
 * sees {@code screenFetch} decorate the fetcher the miss leg invokes and {@code verifyHit} fire before a hit serves; a
 * {@link PullThroughHooks.HitDecision#withhold() withhold} decision answers {@code 404} without ever serving the local
 * bytes and without a miss-leg re-fetch; and a {@link PullThroughHooks.HitDecision#serveLocal serveLocal} decision hands
 * serving of the local bytes to the edition (the downstream fail-closed re-screen), still no upstream fetch.
 */
public class PullThroughHooksTest {

    private static final byte[] HIT = "cached-locally".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPSTREAM = "from-the-upstream".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERIFIED = "re-screened-local".getBytes(StandardCharsets.UTF_8);
    private static final URI UPSTREAM_BASE = URI.create("https://upstream.test/");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void none_serve_through_is_byte_identical_on_a_hit() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/a", HIT);
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/a");

        // The default constructor binds PullThroughHooks.NONE.
        new PullThroughCache(format.fetcher).serve(format, format, UPSTREAM_BASE, exchange, store);

        assertThat(exchange.status).isEqualTo(200);
        assertThat(exchange.responseBody).as("the local hit is served byte-for-byte").isEqualTo(HIT);
        assertThat(format.fetches.get()).as("a hit never touches the upstream").isZero();
    }

    @Test
    void a_spy_sees_screenFetch_decorate_the_miss_fetcher_and_verifyHit_fire_on_a_hit() throws IOException {
        SpyFormat format = new SpyFormat();
        format.upstream.put(UPSTREAM_BASE + "spyproxy/miss", UPSTREAM);
        SpyHooks hooks = new SpyHooks();

        // A miss: no local copy, the upstream has it. The screened fetcher must be the one the proxy leg invokes.
        FakeExchange miss = new FakeExchange("GET", "/spyproxy/miss");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, miss, store);

        assertThat(miss.status).isEqualTo(200);
        assertThat(miss.responseBody).isEqualTo(UPSTREAM);
        assertThat(hooks.screenFetchPaths).as("screenFetch is offered the miss path").containsExactly("/spyproxy/miss");
        assertThat(hooks.decoratedFetches.get()).as("the decorated fetcher is the one the miss leg invoked").isEqualTo(1);

        // A hit: verifyHit fires before serving, with the claiming format, path and scoped store.
        format.local.put("/spyproxy/hit", HIT);
        FakeExchange hit = new FakeExchange("GET", "/spyproxy/hit");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, hit, store);

        assertThat(hit.status).isEqualTo(200);
        assertThat(hit.responseBody).isEqualTo(HIT);
        assertThat(hooks.verifyHitPaths).as("verifyHit fires on the hit path").contains("/spyproxy/hit");
        assertThat(hooks.verifiedFormat).as("verifyHit receives the claiming format").isSameAs(format);
        assertThat(hooks.verifiedStore).as("verifyHit receives the scoped store").isSameAs(store);
        assertThat(hooks.screenFetchPaths).as("a hit runs no miss leg, so screenFetch is not offered the hit path")
                .doesNotContain("/spyproxy/hit");
    }

    @Test
    void an_answer_kept_under_another_path_is_screened_filled_and_served_under_it() throws IOException {
        SpyFormat format = new SpyFormat();
        format.upstream.put(UPSTREAM_BASE + "spyproxy/branch", UPSTREAM);
        // The upstream names what the branch answers with - a commit - so the fill is one content under one name.
        format.keptAs = Map.of("/spyproxy/branch", "/spyproxy/commit");
        SpyHooks hooks = new SpyHooks();

        FakeExchange miss = new FakeExchange("GET", "/spyproxy/branch");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, miss, store);

        assertThat(miss.status).isEqualTo(200);
        assertThat(miss.responseBody).as("the client is answered with what it asked for").isEqualTo(UPSTREAM);
        assertThat(hooks.screenFetchPaths).as("the screen decides under the kept path").containsExactly("/spyproxy/commit");
        assertThat(format.proxied).as("the leg keeps under the kept path and fetches what was requested")
                .containsExactly("/spyproxy/commit <- /spyproxy/branch");
        assertThat(format.local).containsOnlyKeys("/spyproxy/commit");
    }

    @Test
    void a_format_that_keeps_nothing_elsewhere_hands_its_leg_the_request_unchanged() throws IOException {
        SpyFormat format = new SpyFormat();
        format.upstream.put(UPSTREAM_BASE + "spyproxy/plain", UPSTREAM);
        SpyHooks hooks = new SpyHooks();

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE,
                new FakeExchange("GET", "/spyproxy/plain"), store);

        assertThat(format.proxied).containsExactly("/spyproxy/plain <- /spyproxy/plain");
        assertThat(hooks.screenFetchPaths).containsExactly("/spyproxy/plain");
    }

    @Test
    void a_withhold_decision_answers_404_without_serving_the_local_bytes() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/withheld", HIT);
        SpyHooks hooks = new SpyHooks();
        hooks.decision = PullThroughHooks.HitDecision.withhold();
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/withheld");

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, exchange, store);

        assertThat(exchange.status).as("a withheld hit is a 404").isEqualTo(404);
        assertThat(exchange.responseBody).as("the local bytes are never served").isEmpty();
        assertThat(format.handles.get()).as("the format's local-first serve never ran").isZero();
        assertThat(format.fetches.get()).as("a withhold does not re-fetch via the miss leg").isZero();
    }

    @Test
    void a_serveLocal_decision_hands_serving_of_the_local_bytes_to_the_edition() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/verify", HIT);
        SpyHooks hooks = new SpyHooks();
        // The edition serves the local bytes itself, fail-closed - here it re-screens and streams verified bytes.
        hooks.decision = PullThroughHooks.HitDecision.serveLocal(
                (fmt, ex, st) -> ex.respond(200, VERIFIED));
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/verify");

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, exchange, store);

        assertThat(exchange.status).isEqualTo(200);
        assertThat(exchange.responseBody).as("the edition served the (re-screened) local bytes").isEqualTo(VERIFIED);
        assertThat(format.handles.get()).as("the cache's own local-first serve did not run").isZero();
        assertThat(format.fetches.get()).as("a local re-verify never fetches upstream").isZero();
    }

    @Test
    void companions_are_fetched_before_the_screen_and_kept_beside_a_served_artifact() throws IOException {
        SpyFormat format = new SpyFormat();
        byte[] signature = "-----BEGIN PGP SIGNATURE-----".getBytes(StandardCharsets.UTF_8);
        format.upstream.put(UPSTREAM_BASE + "spyproxy/lib.jar", UPSTREAM);
        format.upstream.put(UPSTREAM_BASE + "spyproxy/lib.jar.asc", signature);
        // no .sigstore.json upstream: absence, never a failure
        format.companions = List.of(
                new ProxyFormat.Companion("/spyproxy/lib.jar.asc", URI.create(UPSTREAM_BASE + "spyproxy/lib.jar.asc")),
                new ProxyFormat.Companion("/spyproxy/lib.jar.sigstore.json",
                        URI.create(UPSTREAM_BASE + "spyproxy/lib.jar.sigstore.json")));
        SpyHooks hooks = new SpyHooks();
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/lib.jar");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, exchange, store);
        assertThat(exchange.status).isEqualTo(200);
        assertThat(hooks.companions).as("the screen was handed what arrived, keyed by the path it is kept at")
                .containsOnlyKeys("/spyproxy/lib.jar.asc");
        assertThat(hooks.companions.get("/spyproxy/lib.jar.asc")).isEqualTo(signature);
        assertThat(format.fetches.get()).as("one fetch per companion, once, beside the artifact's own").isEqualTo(3);
        Publication publication = new Publication(store);
        Optional<String> kept = publication.located("/spyproxy/lib.jar.asc");
        assertThat(kept).as("a companion of a served artifact is linked at its own path").isPresent();
        assertThat(store.exists(kept.get())).isTrue();
        assertThat(publication.located("/spyproxy/lib.jar.sigstore.json"))
                .as("a companion the upstream does not publish is kept nowhere").isEmpty();
    }

    @Test
    void a_companion_past_the_bound_or_of_an_artifact_that_did_not_fill_is_not_kept() throws IOException {
        SpyFormat format = new SpyFormat();
        byte[] oversized = new byte[ArtifactSignatures.Material.LARGEST_SIGNATURE + 1];
        format.upstream.put(UPSTREAM_BASE + "spyproxy/big.jar", UPSTREAM);
        format.upstream.put(UPSTREAM_BASE + "spyproxy/big.jar.asc", oversized);
        format.companions = List.of(
                new ProxyFormat.Companion("/spyproxy/big.jar.asc", URI.create(UPSTREAM_BASE + "spyproxy/big.jar.asc")));
        SpyHooks hooks = new SpyHooks();
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE,
                new FakeExchange("GET", "/spyproxy/big.jar"), store);
        assertThat(hooks.companions).as("a companion past the signature bound is not a signature we can check").isEmpty();
        assertThat(new Publication(store).located("/spyproxy/big.jar.asc")).isEmpty();

        // The artifact itself is absent upstream: its companion, present or not, is kept nowhere, since there is
        // nothing for it to be a sidecar of.
        format.upstream.put(UPSTREAM_BASE + "spyproxy/gone.jar.asc", "sig".getBytes(StandardCharsets.UTF_8));
        format.companions = List.of(
                new ProxyFormat.Companion("/spyproxy/gone.jar.asc", URI.create(UPSTREAM_BASE + "spyproxy/gone.jar.asc")));
        FakeExchange miss = new FakeExchange("GET", "/spyproxy/gone.jar");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, miss, store);
        assertThat(miss.status).isEqualTo(404);
        assertThat(new Publication(store).located("/spyproxy/gone.jar.asc")).isEmpty();
    }

    @Test
    void a_format_that_keeps_a_companion_itself_has_it_linked_nowhere_else() throws IOException {
        SpyFormat format = new SpyFormat();
        byte[] document = "[]".getBytes(StandardCharsets.UTF_8);
        format.upstream.put(UPSTREAM_BASE + "spyproxy/widget.gem", UPSTREAM);
        format.upstream.put(UPSTREAM_BASE + "api/attestations/widget.json", document);
        format.companions = List.of(new ProxyFormat.Companion("/spyproxy/attestations/widget.json",
                URI.create(UPSTREAM_BASE + "api/attestations/widget.json")));
        format.keeps = true;
        new PullThroughCache(format.fetcher, new SpyHooks()).serve(format, format, UPSTREAM_BASE,
                new FakeExchange("GET", "/spyproxy/widget.gem"), store);
        assertThat(format.kept).as("the format was handed the document to keep under a key of its own")
                .containsEntry("/spyproxy/attestations/widget.json", document);
        assertThat(new Publication(store).located("/spyproxy/attestations/widget.json"))
                .as("and the cache linked it nowhere, since the format answered that it kept it").isEmpty();
    }

    /** A spy format that is also its own {@link ProxyFormat}: a local map answers hits, an upstream map answers the
     *  proxy leg, and counters record how often it served locally and fetched upstream. */
    private static final class SpyFormat implements RepositoryFormat, ProxyFormat {
        private List<ProxyFormat.Companion> companions = List.of();
        private Map<String, String> keptAs = Map.of();
        private final List<String> proxied = new ArrayList<>();

        @Override
        public Optional<String> keptAs(FormatExchange exchange, URI upstream, ProxyFormat.Fetcher fetcher) {
            return Optional.ofNullable(keptAs.get(exchange.path()));
        }
        private boolean keeps;
        private final Map<String, byte[]> kept = new HashMap<>();

        @Override
        public List<ProxyFormat.Companion> companions(FormatExchange exchange, URI upstream) {
            return companions;
        }

        @Override
        public boolean keep(ArtifactStore store, ProxyFormat.Companion companion, byte[] body) {
            if (!keeps) {
                return false;
            }
            kept.put(companion.path(), body);
            return true;
        }


        private final Map<String, byte[]> local = new HashMap<>();
        private final Map<String, byte[]> upstream = new HashMap<>();
        private final AtomicInteger handles = new AtomicInteger();
        private final AtomicInteger fetches = new AtomicInteger();

        private final ProxyFormat.Fetcher.Buffered fetcher = (url, headers) -> {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };

        @Override
        public String name() {
            return "spyproxy";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/spyproxy/");
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            handles.incrementAndGet();
            byte[] body = local.get(exchange.path());
            if (body == null) {
                exchange.respond(404);
                return;
            }
            exchange.respond(200, body);
        }

        @Override
        public boolean proxy(FormatExchange exchange, ArtifactStore store, URI base, ProxyFormat.Fetcher fetcher)
                throws IOException {
            proxied.add(exchange.path() + " <- " + exchange.requestedPath());
            Optional<ProxyFormat.Fetched> fetched =
                    fetcher.fetch(base.resolve(exchange.requestedPath().substring(1)), Map.of());
            if (fetched.isEmpty() || fetched.get().status() != 200) {
                return false;
            }
            byte[] body = fetched.get().body();
            local.put(exchange.path(), body); // cache, so a later read is a local hit
            exchange.respond(200, body);
            return true;
        }
    }

    /** A spy {@link PullThroughHooks}: records every {@code verifyHit}/{@code screenFetch} call and the args it saw,
     *  wraps the fetcher with a counting decorator, and returns a configurable hit decision (serve-through by default). */
    private static final class SpyHooks implements PullThroughHooks {

        private final List<String> verifyHitPaths = new ArrayList<>();
        private final List<String> screenFetchPaths = new ArrayList<>();
        private final AtomicInteger decoratedFetches = new AtomicInteger();
        private Map<String, byte[]> companions = Map.of();
        private RepositoryFormat verifiedFormat;
        private ArtifactStore verifiedStore;
        private HitDecision decision = HitDecision.serveThrough();

        @Override
        public HitDecision verifyHit(RepositoryFormat format, String path, ArtifactStore store) {
            verifyHitPaths.add(path);
            verifiedFormat = format;
            verifiedStore = store;
            return decision;
        }

        @Override
        public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store,
                                               Map<String, byte[]> companions) {
            this.companions = companions;
            return screenFetch(path, upstream, store);
        }

        @Override
        public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store) {
            screenFetchPaths.add(path);
            // A screen is a DECORATOR, so it delegates all three legs rather than deriving two of them: a hook that
            // implemented only fetch would replace the wrapped transport's streaming download and real HTTP HEAD with
            // buffered derivations, collapsing the deployment's streaming path from inside a screen that says nothing
            // about bodies at all.
            return new ProxyFormat.Fetcher() {

                @Override
                public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) throws IOException {
                    decoratedFetches.incrementAndGet();
                    return upstream.fetch(url, headers);
                }

                @Override
                public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers)
                        throws IOException {
                    return upstream.download(url, headers);
                }

                @Override
                public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) throws IOException {
                    return upstream.head(url, headers);
                }
            };
        }
    }

    /** A minimal {@link FormatExchange} capturing the status and body a serve wrote. */
    private static final class FakeExchange implements FormatExchange {

        private final String method;
        private final String path;
        private int status = -1;
        private byte[] responseBody = new byte[0];

        private FakeExchange(String method, String path) {
            this.method = method;
            this.path = path;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    responseBody = toByteArray();
                }
            };
        }
    }
}
