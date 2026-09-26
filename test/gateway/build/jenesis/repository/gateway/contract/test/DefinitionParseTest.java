package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.definitions.RepositoryDefinition.Fallback;
import build.jenesis.repository.definitions.RepositoryDefinition.Screening;
import build.jenesis.repository.definitions.RepositoryDefinition.Source;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parse/model layer: the generalized {@link RepositoryDefinition} record - {@code writable} plus an ordered
 * list of {@link Fallback}s (each an {@link Source.Upstream} URL or a {@link Source.Repository} name, with a
 * per-upstream {@code store} and {@link Screening} policy) - the clause grammar that produces it, the desugar of the
 * old {@code hosted}/{@code proxy}/{@code group} spellings into the same record, the parse
 * refusals (fail-loud, §9), and the mixed-strength warn (⚑ warn, not refuse).
 *
 * <p>These assertions are additive: the legacy routing/resolution behavior stays proven by the unchanged
 * {@code RepositoryRouterTest}/{@code MavenRouterTest}/{@code GatedRouterTest}/{@code PassThroughStreamingTest};
 * {@link #the_new_fallback_spelling_routes_through_the_unchanged_legacy_walk()} adds one proof that the new
 * {@code fallback <url>} spelling resolves identically to {@code proxy <url>} through that unchanged walk.
 */
public class DefinitionParseTest {

    @TempDir
    Path root;

    // ---- Mapping table: every old spelling desugars to the right generalized record ---------------------------

    @Test
    public void hosted_and_the_hosted_factory_desugar_to_writable_with_no_fallbacks() {
        for (RepositoryDefinition hosted : List.of(RepositoryDefinition.parse("hosted"), RepositoryDefinition.hosted())) {
            assertThat(hosted.writable()).as("hosted accepts uploads into its own store").isTrue();
            assertThat(hosted.fallbacks()).as("hosted has no fallbacks").isEmpty();
        }
    }

    @Test
    public void proxy_desugars_to_a_non_writable_single_upstream_fallback() {
        RepositoryDefinition proxy = RepositoryDefinition.parse("proxy https://repo1.maven.org/maven2");
        assertThat(proxy.writable()).isFalse();
        assertThat(proxy.fallbacks()).singleElement().satisfies(fallback -> {
            assertThat(fallback.source()).isEqualTo(new Source.Upstream(URI.create("https://repo1.maven.org/maven2")));
            assertThat(fallback.store()).as("a proxy caches by default").isTrue();
            assertThat(fallback.screening()).isEqualTo(Screening.DEFAULT);
        });
        // the non-throwing legacy derived views are unchanged
        assertThat(proxy.upstream()).isEqualTo(URI.create("https://repo1.maven.org/maven2"));
        assertThat(proxy.cache()).isTrue();
        assertThat(proxy.harden()).isFalse();
        assertThat(proxy.members()).isEmpty();
    }

    @Test
    public void the_proxy_mode_tokens_map_onto_store_and_screening() {
        assertThat(only(RepositoryDefinition.parse("proxy https://up/ nocache")))
                .as("nocache -> no-store, DEFAULT screening")
                .isEqualTo(new Fallback(new Source.Upstream(URI.create("https://up/")), false, Screening.DEFAULT));
        assertThat(only(RepositoryDefinition.parse("proxy https://up/ harden")))
                .as("harden -> store-on-pass, HARDEN screening")
                .isEqualTo(new Fallback(new Source.Upstream(URI.create("https://up/")), true, Screening.HARDEN));
        assertThat(only(RepositoryDefinition.parse("proxy https://up/ harden nocache")))
                .as("harden nocache -> no-store, HARDEN screening")
                .isEqualTo(new Fallback(new Source.Upstream(URI.create("https://up/")), false, Screening.HARDEN));
        assertThat(only(RepositoryDefinition.parse("proxy https://up/ nocache harden")))
                .as("token order does not matter")
                .isEqualTo(new Fallback(new Source.Upstream(URI.create("https://up/")), false, Screening.HARDEN));
    }

    @Test
    public void group_desugars_to_non_writable_repository_fallbacks_in_order() {
        RepositoryDefinition group = RepositoryDefinition.parse("group a,b,c");
        assertThat(group.writable()).isFalse();
        assertThat(group.fallbacks()).containsExactly(
                new Fallback(new Source.Repository("a"), false, Screening.DEFAULT),
                new Fallback(new Source.Repository("b"), false, Screening.DEFAULT),
                new Fallback(new Source.Repository("c"), false, Screening.DEFAULT));
        assertThat(group.members()).containsExactly("a", "b", "c");
    }

    // ---- The new clause grammar ------------------------------------------------------------------------------

    @Test
    public void writable_alone_parses_to_a_hosted_shaped_record() {
        RepositoryDefinition writable = RepositoryDefinition.parse("writable");
        assertThat(writable.writable()).isTrue();
        assertThat(writable.fallbacks()).isEmpty();
    }

    @Test
    public void the_default_cache_policy_of_a_bare_fallback_is_store() {
        // §pin: a bare `fallback <url>` (and the legacy `proxy <url>` spelling it desugars from) defaults to
        // store=true - the caching-proxy default. `nocache` is the explicit opt-out to a discard-after-serve pass.
        assertThat(only(RepositoryDefinition.parse("fallback https://up/")).store())
                .as("a bare 'fallback <url>' caches its fetched bytes by default").isTrue();
        assertThat(only(RepositoryDefinition.parse("proxy https://up/")).store())
                .as("the legacy 'proxy <url>' spelling defaults to store too").isTrue();
        assertThat(only(RepositoryDefinition.parse("fallback https://up/ nocache")).store())
                .as("'nocache' is the explicit opt-out of the store default").isFalse();
    }

    @Test
    public void a_fallback_url_parses_to_an_upstream_fallback_with_options_defaulting_to_store_and_default_screen() {
        assertThat(only(RepositoryDefinition.parse("fallback https://repo1.maven.org/maven2")))
                .isEqualTo(new Fallback(new Source.Upstream(URI.create("https://repo1.maven.org/maven2")),
                        true, Screening.DEFAULT));
        assertThat(only(RepositoryDefinition.parse("fallback https://up/ nocache")).store()).isFalse();
        assertThat(only(RepositoryDefinition.parse("fallback https://up/ harden")).screening()).isEqualTo(Screening.HARDEN);
        assertThat(only(RepositoryDefinition.parse("fallback https://up/ unscreened")).screening())
                .as("unscreened parses (with a loud warning), it is not a refusal").isEqualTo(Screening.UNSCREENED);
    }

    @Test
    public void options_bind_to_the_nearest_preceding_fallback() {
        // the plan's `mirror-chain`: cache the primary, pass-through the spillover
        RepositoryDefinition mirrorChain = RepositoryDefinition.parse("fallback https://mirror-a/ fallback https://mirror-b/ nocache");
        assertThat(mirrorChain.fallbacks()).containsExactly(
                new Fallback(new Source.Upstream(URI.create("https://mirror-a/")), true, Screening.DEFAULT),
                new Fallback(new Source.Upstream(URI.create("https://mirror-b/")), false, Screening.DEFAULT));

        // an option after the first fallback binds to the first, not to a later one
        RepositoryDefinition edge = RepositoryDefinition.parse("fallback https://corp/artifactory harden nocache");
        assertThat(only(edge)).isEqualTo(
                new Fallback(new Source.Upstream(URI.create("https://corp/artifactory")), false, Screening.HARDEN));
    }

    @Test
    public void writable_is_position_free_and_the_hybrid_host_and_proxy_shape_parses() {
        // the owner's headline case the old model could not express: hosts its own artifacts AND falls back
        RepositoryDefinition frontdoor = RepositoryDefinition.parse("writable fallback releases fallback central-hard");
        assertThat(frontdoor.writable()).isTrue();
        assertThat(frontdoor.fallbacks()).containsExactly(
                new Fallback(new Source.Repository("releases"), false, Screening.DEFAULT),
                new Fallback(new Source.Repository("central-hard"), false, Screening.DEFAULT));

        // writable may appear anywhere (position-free), once
        RepositoryDefinition trailing = RepositoryDefinition.parse("fallback https://up/ writable");
        assertThat(trailing.writable()).isTrue();
        assertThat(trailing.fallbacks()).hasSize(1);
    }

    // ---- validation / fail-loud (§9) ------------------------------------------------------------------------------

    @Test
    public void a_repository_name_fallback_carrying_a_policy_option_is_refused() {
        for (String option : List.of("nocache", "harden", "unscreened")) {
            assertThatThrownBy(() -> RepositoryDefinition.parse("fallback inner " + option))
                    .as("'%s' on a repository-name fallback", option)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("owns its own store and screening policy");
        }
    }

    @Test
    public void a_non_writable_definition_with_no_fallbacks_is_refused() {
        assertThatThrownBy(() -> new RepositoryDefinition(false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can never serve anything")
                .hasMessageContaining("writable");
    }

    @Test
    public void malformed_clause_definitions_fail_with_a_clear_error() {
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback"))
                .as("a fallback clause with no source")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a source");
        assertThatThrownBy(() -> RepositoryDefinition.parse("writable nocache"))
                .as("an option with no preceding fallback")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow a 'fallback'");
        assertThatThrownBy(() -> RepositoryDefinition.parse("writable writable"))
                .as("writable may appear at most once")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most once");
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback https://up/ bogus"))
                .as("an unknown clause token")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown definition token");
    }

    // ---- mixed-strength: ⚑ warn, never a refusal -----------------------------------------------------------

    @Test
    public void a_mixed_strength_upstream_fallback_list_is_flagged_but_parses() {
        RepositoryDefinition mixed = RepositoryDefinition.parse("fallback https://a/ harden fallback https://b/");
        // it parses (not a refusal) with both fallbacks intact...
        assertThat(mixed.fallbacks()).containsExactly(
                new Fallback(new Source.Upstream(URI.create("https://a/")), true, Screening.HARDEN),
                new Fallback(new Source.Upstream(URI.create("https://b/")), true, Screening.DEFAULT));
        // ...and the classifier that drives the loud warn reports the hazard (tested like plaintextUpstream)
        assertThat(RepositoryDefinition.mixedStrength(mixed.fallbacks())).as("a harden upstream beside a weaker one").isTrue();

        assertThat(RepositoryDefinition.mixedStrength(RepositoryDefinition.parse("fallback https://a/ harden fallback https://b/ harden")
                .fallbacks())).as("all hardened - not mixed").isFalse();
        assertThat(RepositoryDefinition.mixedStrength(RepositoryDefinition.parse("fallback https://a/ fallback https://b/").fallbacks()))
                .as("all default - not mixed").isFalse();
        assertThat(RepositoryDefinition.mixedStrength(RepositoryDefinition.parse("fallback https://a/ harden").fallbacks()))
                .as("a lone hardened upstream is not mixed").isFalse();
    }

    // ---- push=: hard parse refusal (cutover) -----------------------------------------------------------

    @Test
    public void a_group_push_directive_is_a_hard_parse_refusal_naming_both_remedies() {
        // Hard cutover: `group … push=…` delegated a write into a member's store; writability is now a
        // repository's own property, so this spelling has no honest desugar (a silent rewrite would move where uploaded
        // bytes land - a §9 violation). It is refused at parse, naming both remedies: declare the front repository
        // writable, or point publishers at the member.
        assertThatThrownBy(() -> RepositoryDefinition.parse("group a,b push=a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer supported")
                .hasMessageContaining("writable")
                .hasMessageContaining("directly at 'a'");
    }

    // ---- behavior preservation through the unchanged legacy walk --------------------------------------------------

    @Test
    public void the_new_fallback_spelling_routes_through_the_unchanged_legacy_walk() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Map<String, byte[]> upstream = Map.of("http://up/remote.txt", "from upstream".getBytes(StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        ProxyFormat.Fetcher.Buffered fetcher = (url, _) -> {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };
        Map<String, RepositoryDefinition> definitions = Map.of(
                "sugar", RepositoryDefinition.parse("fallback http://up/"),            // new spelling of a caching proxy
                "sugar-nocache", RepositoryDefinition.parse("fallback http://up/ nocache"));
        RepositoryRouter router = new RepositoryRouter(definitions::get,
                (tenant, repository) -> store.scope(tenant).scope(repository), fetcher);

        // `fallback http://up/` caches exactly like `proxy http://up/`: one fetch, then served from the store
        assertThat(get(router, "sugar", "/t/remote.txt")).isEqualTo("from upstream");
        assertThat(fetches.get()).isEqualTo(1);
        assertThat(get(router, "sugar", "/t/remote.txt")).isEqualTo("from upstream");
        assertThat(fetches.get()).as("served from the cache, no second fetch").isEqualTo(1);

        // the new spelling of a non-writable single upstream is read-only, exactly as a proxy is
        assertThat(router.writeTarget("sugar")).isNull();

        // `fallback http://up/ nocache` streams without storing: each read fetches again
        int before = fetches.get();
        assertThat(get(router, "sugar-nocache", "/t/remote.txt")).isEqualTo("from upstream");
        assertThat(get(router, "sugar-nocache", "/t/remote.txt")).isEqualTo("from upstream");
        assertThat(fetches.get()).as("nothing cached - fetched each time").isEqualTo(before + 2);
    }

    private static Fallback only(RepositoryDefinition definition) {
        assertThat(definition.fallbacks()).hasSize(1);
        return definition.fallbacks().getFirst();
    }

    private static String get(RepositoryRouter router, String repository, String path) throws IOException {
        TestExchange exchange = new TestExchange("GET", path, new byte[0]);
        router.serve("acme", repository, new TestFormat(), exchange);
        assertThat(exchange.status).as("GET " + repository + path).isEqualTo(200);
        return new String(exchange.responseBody(), StandardCharsets.UTF_8);
    }

    /** A trivial format: a GET serves stored bytes, a miss proxies one upstream (mirrors RepositoryRouterTest). */
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

    /** A {@link FormatExchange} that records the response so a test can assert it (mirrors RepositoryRouterTest). */
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
