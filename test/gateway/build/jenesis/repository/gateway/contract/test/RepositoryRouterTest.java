package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the repository model end to end without the network: a {@code writable} repository, a caching proxy
 * ({@code fallback <url>}) and a pass-through ({@code fallback <url> nocache}) of a fixed upstream, and two views over
 * them ({@code fallback a fallback b}) - one a caching front door, one a non-storing view. Asserts first-hit-wins read
 * routing, that a caching proxy stores the fetched bytes while a pass-through does not, and that a write lands in a
 * repository's own store iff it is writable (a view and a proxy are read-only).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryRouterTest {

    @TempDir
    static Path root;

    private RepositoryRouter router;
    private ArtifactStore store;
    private AtomicInteger fetches;

    @BeforeAll
    public void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Map<String, byte[]> upstream = Map.of("http://up/remote.txt", "from the upstream".getBytes(StandardCharsets.UTF_8));
        fetches = new AtomicInteger();
        ProxyFormat.Fetcher.Buffered fetcher = (url, _) -> {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };
        Map<String, RepositoryDefinition> definitions = Map.of(
                "releases", RepositoryDefinition.parse("writable"),
                "central", RepositoryDefinition.parse("fallback http://up/"),
                "passthru", RepositoryDefinition.parse("fallback http://up/ nocache"),
                "public", RepositoryDefinition.parse("fallback releases fallback central"),
                "view", RepositoryDefinition.parse("fallback releases fallback passthru"));
        router = new RepositoryRouter(definitions::get,
                (tenant, repository) -> store.scope(tenant).scope(repository), fetcher);

        // seed the writable member with a first-party artifact
        put("releases", "/t/local.txt", "first party");
    }

    @Test
    public void the_writable_proxy_and_view_shapes_route_reads_and_writes() throws Exception {
        // a view reads a first-party artifact from its writable member
        assertThat(get("public", "/t/local.txt")).isEqualTo("first party");

        // a view's miss falls through to the caching proxy member, which fetches once and stores it
        assertThat(get("public", "/t/remote.txt")).isEqualTo("from the upstream");
        assertThat(fetches.get()).isEqualTo(1);
        assertThat(get("central", "/t/remote.txt")).as("the proxy cached it").isEqualTo("from the upstream");
        assertThat(fetches.get()).as("served from the cache, no second fetch").isEqualTo(1);

        // an artifact absent everywhere is a 404
        assertThat(serve("public", "/t/absent.txt").status).isEqualTo(404);

        // a view over a pass-through proxy streams without storing: each read fetches again
        int before = fetches.get();
        assertThat(get("view", "/t/remote.txt")).isEqualTo("from the upstream");
        assertThat(fetches.get()).as("the pass-through fetched").isEqualTo(before + 1);
        assertThat(get("view", "/t/remote.txt")).isEqualTo("from the upstream");
        assertThat(fetches.get()).as("nothing was cached - fetched again").isEqualTo(before + 2);

        // a write lands in the repository's OWN store iff writable; a proxy and a view are read-only
        assertThat(router.writeTarget("releases")).as("a writable repo is its own write target").isEqualTo("releases");
        assertThat(router.writeTarget("public")).as("a view is read-only, and delegates no write").isNull();
        assertThat(router.writeTarget("central")).as("a proxy is read-only").isNull();

        // a first-party upload into the writable member is served back through the view, local-first over the member
        put("releases", "/t/pushed.txt", "pushed through");
        assertThat(get("releases", "/t/pushed.txt")).isEqualTo("pushed through");
        assertThat(get("public", "/t/pushed.txt")).isEqualTo("pushed through");
    }

    @Test
    public void a_malformed_definition_is_a_clear_error_not_an_index_out_of_bounds() {
        // A stored setting parsed per request must fail with a clear, caught IllegalArgumentException rather than an
        // ArrayIndexOutOfBoundsException that would 500 every request to the repo.
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a source");
        assertThatThrownBy(() -> RepositoryDefinition.parse("proxy"))
                .as("a word outside the grammar, with nothing after it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("write 'fallback <url>'");
        assertThatThrownBy(() -> RepositoryDefinition.parse("group   push=releases"))
                .as("a word outside the grammar whose only token is a directive")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'fallback a fallback b'");
        assertThatThrownBy(() -> RepositoryDefinition.parse("teleport somewhere"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("written in clauses");
    }

    @Test
    public void a_non_https_proxy_upstream_is_classified_through_the_one_shared_rule() {
        // This classifier no longer restates "is it https" - it delegates to the shared
        // PrivateHostGuard.cleartextRefusal rule every other outbound leg reads, so the product cannot answer "is this
        // upstream's transport acceptable" in two implementations again (it did: this one, and the importer's
        // RepositoryAutoConfiguration.isInsecureUpstream, only one of which held the reasoning).
        assertThat(RepositoryDefinition.plaintextUpstream(URI.create("http://up/")))
                .as("a plain http upstream is cleartext").isTrue();
        assertThat(RepositoryDefinition.plaintextUpstream(URI.create("HTTP://up/")))
                .as("the scheme test is case-insensitive").isTrue();
        assertThat(RepositoryDefinition.plaintextUpstream(URI.create("https://up/")))
                .as("an https upstream is the secure default").isFalse();

        // parse() stays a pure grammar parser with no configuration and no I/O: it still yields a usable proxy
        // definition with the upstream intact, so a console READ can render a stored definition's shape without
        // resolving anything (§10). Whether the deployment may PULL from that upstream is the separate, dialled
        // question below - asked where an operator configures it, not where a string is parsed.
        RepositoryDefinition proxy = RepositoryDefinition.parse("fallback http://up/");
        assertThat(proxy.writable()).as("a proxy is not writable").isFalse();
        assertThat(only(proxy).source()).isEqualTo(new RepositoryDefinition.Source.Upstream(URI.create("http://up/")));
    }

    @Test
    public void an_operator_configured_plaintext_upstream_is_refused_unless_the_deployment_takes_the_dial() {
        // Every peer operator-configured outbound target refuses a cleartext one - the webhook endpoint, the
        // forwarding target, the emulator target, the redirect directory and the import guard all run the shared
        // screen - and the proxy upstream was the only one that merely warned, while carrying a per-host upstream
        // credential to whatever host is named.
        assertThat(RepositoryDefinition.upstreamRefusal(URI.create("http://up/"), false))
                .as("a plaintext upstream is refused by default").contains("not https");
        assertThat(RepositoryDefinition.upstreamRefusal(URI.create("https://up/"), false))
                .as("https is the admissible transport").isNull();
        assertThat(RepositoryDefinition.upstreamRefusal(URI.create("http://up/"), true))
                .as("and the deployment-global dial is the whole opt-out, for the internal plaintext mirror").isNull();

        // The host half is deliberately NOT applied: unlike a webhook callback this is the operator's own choice of
        // where to pull from, an internal mirror is a legitimate deployment, and the console renders a stored
        // definition on a GET where a DNS resolution would be an external fetch on a read path.
        assertThat(RepositoryDefinition.upstreamRefusal(URI.create("https://127.0.0.1:8081/repo"), false))
                .as("an internal https mirror is the operator's own choice and stays usable").isNull();

        // The capability floor underneath is not the dial's to lift: no setting makes a non-http(s) upstream fetchable.
        assertThat(RepositoryDefinition.upstreamRefusal(URI.create("gopher://up/"), true)).isNotNull();

        // And it screens the whole fallback list, not just its first leg.
        assertThat(RepositoryDefinition.upstreamRefusal(RepositoryDefinition.parse("fallback https://a/ fallback http://b/"), false))
                .as("a plaintext leg anywhere in the list is refused, naming it").contains("http://b/");
        assertThat(RepositoryDefinition.upstreamRefusal(RepositoryDefinition.parse("fallback https://a/ fallback https://b/"), false))
                .isNull();
        assertThat(RepositoryDefinition.upstreamRefusal(RepositoryDefinition.parse("fallback a fallback b"), false))
                .as("a repository-name fallback names no outbound target at all").isNull();
    }

    @Test
    public void a_harden_proxy_definition_parses_as_a_caching_screening_proxy() {
        // `fallback <url> harden` is the opt-in untrusted-upstream hardening proxy: it screens every fetched body
        // in full before releasing any byte, and is store-on-pass - a passed artifact is durably cached as trusted, so
        // harden implies caching.
        RepositoryDefinition harden = RepositoryDefinition.parse("fallback http://up/ harden");
        assertThat(harden.writable()).as("a harden proxy is not writable").isFalse();
        assertThat(harden.harden()).as("harden is opt-in").isTrue();
        assertThat(only(harden).store()).as("harden is store-on-pass, so it caches the verified copy").isTrue();
        assertThat(only(harden).source()).isEqualTo(new RepositoryDefinition.Source.Upstream(URI.create("http://up/")));

        RepositoryDefinition caching = RepositoryDefinition.parse("fallback http://up/");
        assertThat(caching.harden()).as("a plain caching proxy is not hardened").isFalse();
        RepositoryDefinition nocache = RepositoryDefinition.parse("fallback http://up/ nocache");
        assertThat(nocache.harden()).isFalse();
        assertThat(only(nocache).store()).as("nocache is the opposite of store-on-pass").isFalse();

        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback http://up/ bogus"))
                .as("an unknown option is refused, not silently treated as a plain caching proxy")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown definition token");
    }

    @Test
    public void a_hardened_upstream_anywhere_in_the_definition_is_recognized_as_hardened() {
        // harden() gates the cache-hit re-verify and the redirect-serve exclusion. A hardened upstream is not only a
        // definition whose one clause is `fallback <url> harden`: a writable repo can carry one (uploads plus a
        // hardened cache leg), and so can a list beside other fallbacks. Those shapes MUST also report harden()==true,
        // or their cached hardened bytes would serve/redirect without the per-hit re-screen - the exact bypass this
        // recognizes. mixedStrength flags such a list at parse but never refuses it, so the shapes are reachable.
        assertThat(RepositoryDefinition.parse("writable fallback http://up/ harden").harden())
                .as("a writable repo with a hardened upstream fallback is a hardening proxy").isTrue();
        assertThat(RepositoryDefinition.parse("fallback http://a/ harden fallback http://b/").harden())
                .as("a hardened upstream anywhere in a multi-fallback list is a hardening proxy").isTrue();
        // A repo with NO hardened upstream stays non-hardened, so a plain caching hybrid or a view is redirect-safe
        // and skips the re-verify.
        assertThat(RepositoryDefinition.parse("writable fallback http://up/").harden())
                .as("a writable caching hybrid with a plain upstream is not hardened").isFalse();
        assertThat(RepositoryDefinition.parse("fallback repoA fallback repoB").harden())
                .as("a view of repository fallbacks carries no static hardened upstream").isFalse();
    }

    @Test
    public void a_harden_nocache_proxy_parses_as_a_screening_non_caching_proxy() {
        // `fallback <url> harden nocache` is the transient full-enforcement screen - full-screen every fetch
        // (harden=true) but store nothing durably (store=false), the opt-in "don't grow my store" hardening proxy.
        RepositoryDefinition hardenNocache = RepositoryDefinition.parse("fallback http://up/ harden nocache");
        assertThat(hardenNocache.writable()).as("a harden nocache proxy is not writable").isFalse();
        assertThat(hardenNocache.harden()).as("harden nocache fully screens every fetch").isTrue();
        assertThat(only(hardenNocache).store()).as("harden nocache stores nothing durably").isFalse();
        assertThat(only(hardenNocache).source())
                .isEqualTo(new RepositoryDefinition.Source.Upstream(URI.create("http://up/")));

        // The two options are order-independent.
        RepositoryDefinition nocacheHarden = RepositoryDefinition.parse("fallback http://up/ nocache harden");
        assertThat(nocacheHarden.harden()).as("option order does not matter").isTrue();
        assertThat(only(nocacheHarden).store()).isFalse();

        // The single-option meanings: plain harden is store-on-pass, plain nocache is an unscreened pass-through.
        assertThat(only(RepositoryDefinition.parse("fallback http://up/ harden")).store())
                .as("plain harden still caches on pass").isTrue();
        assertThat(RepositoryDefinition.parse("fallback http://up/ harden").harden()).isTrue();
        assertThat(RepositoryDefinition.parse("fallback http://up/ nocache").harden())
                .as("plain nocache is not a hardened screen").isFalse();
        assertThat(only(RepositoryDefinition.parse("fallback http://up/ nocache")).store()).isFalse();

        // An unknown token beside the known options is still refused loudly.
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback http://up/ harden bogus"))
                .as("an unknown token beside harden is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown definition token");
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback http://up/ bogus nocache"))
                .as("an unknown token beside nocache is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown definition token");
    }

    @Test
    public void a_harden_nocache_proxy_with_no_screening_installed_fails_loud() throws IOException {
        // Fail-loud (§9) applies to `harden nocache` exactly as to `harden`: it is an explicit opt-in to
        // full screening, so a router with no compliance gate wired must throw at resolution naming what is missing -
        // never a silent fallback to serving an untrusted upstream unscreened.
        Map<String, RepositoryDefinition> definitions = Map.of(
                "hardened-transient", RepositoryDefinition.parse("fallback http://up/ harden nocache"));
        RepositoryRouter unscreened = new RepositoryRouter(definitions::get,
                (tenant, repository) -> store.scope(tenant).scope(repository),
                ProxyFormat.Fetcher.NONE);

        TestExchange exchange = new TestExchange("GET", "/t/evil.txt", new byte[0]);
        assertThatThrownBy(() -> unscreened.serve("acme", "hardened-transient", new TestFormat(), exchange))
                .as("a hardened transient proxy never silently serves an untrusted upstream unscreened")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("harden")
                .hasMessageContaining("screening");
    }

    @Test
    public void a_harden_proxy_with_no_screening_installed_fails_loud_rather_than_proxying_unscreened() {
        // Selected-but-unsatisfiable stays loud (§9): a hardened proxy is an explicit opt-in to full
        // screening, so a router with no compliance gate wired must throw at resolution naming what is missing - never
        // a silent fallback to serving an untrusted upstream unscreened (the store=s3-without-module precedent).
        Map<String, RepositoryDefinition> definitions = Map.of("hardened", RepositoryDefinition.parse("fallback http://up/ harden"));
        RepositoryRouter unscreened = new RepositoryRouter(definitions::get,
                (tenant, repository) -> store.scope(tenant).scope(repository),
                ProxyFormat.Fetcher.NONE);

        TestExchange exchange = new TestExchange("GET", "/t/evil.txt", new byte[0]);
        assertThatThrownBy(() -> unscreened.serve("acme", "hardened", new TestFormat(), exchange))
                .as("a hardened proxy never silently serves an untrusted upstream unscreened")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("harden")
                .hasMessageContaining("screening");
    }

    private static RepositoryDefinition.Fallback only(RepositoryDefinition definition) {
        assertThat(definition.fallbacks()).hasSize(1);
        return definition.fallbacks().getFirst();
    }

    private String get(String repository, String path) throws IOException {
        TestExchange exchange = serve(repository, path);
        assertThat(exchange.status).as("GET " + repository + path).isEqualTo(200);
        return new String(exchange.responseBody(), StandardCharsets.UTF_8);
    }

    private TestExchange serve(String repository, String path) throws IOException {
        TestExchange exchange = new TestExchange("GET", path, new byte[0]);
        router.serve("acme", repository, new TestFormat(), exchange);
        return exchange;
    }

    private void put(String repository, String path, String body) throws IOException {
        new TestFormat().handle(new TestExchange("PUT", path, body.getBytes(StandardCharsets.UTF_8)),
                store.scope("acme").scope(repository));
    }

    /** A trivial format: a PUT stores the bytes content-addressed, a GET serves them, a miss proxies one upstream. */
    private static final class TestFormat implements RepositoryFormat, ProxyFormat {

        @Override
        public String name() {
            return "t";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/t/");
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            Publication publication = new Publication(store);
            String path = exchange.path();
            if (exchange.method().equals("PUT")) {
                publication.link(path, publication.storeBlob(exchange.requestStream()));
                exchange.respond(201);
                return;
            }
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                exchange.respond(404);
                return;
            }
            try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
                store.read(key.get(), out);
            }
        }

        @Override
        public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
                throws IOException {
            String rest = exchange.path().substring("/t/".length());
            String base = upstream.toString();
            Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(
                    URI.create((base.endsWith("/") ? base : base + "/") + rest), Map.of());
            if (fetched.isEmpty() || fetched.get().status() != 200) {
                return false;
            }
            Publication publication = new Publication(store);
            publication.link(exchange.path(), publication.storeBlob(new ByteArrayInputStream(fetched.get().body())));
            handle(exchange, store);
            return true;
        }
    }

    /** A {@link FormatExchange} that records the response so a test can assert it. */
    private static final class TestExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] request;
        private int status = -1;
        private ByteArrayOutputStream response;

        private TestExchange(String method, String path, byte[] request) {
            this.method = method;
            this.path = path;
            this.request = request;
        }

        private byte[] responseBody() {
            return response == null ? new byte[0] : response.toByteArray();
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
            return new ByteArrayInputStream(request);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            this.response = new ByteArrayOutputStream();
            return response;
        }
    }
}
