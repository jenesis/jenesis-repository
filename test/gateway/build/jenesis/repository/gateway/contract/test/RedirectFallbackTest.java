package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.definitions.RepositoryDefinition.Fallback;
import build.jenesis.repository.definitions.RepositoryDefinition.Screening;
import build.jenesis.repository.definitions.RepositoryDefinition.Serve;
import build.jenesis.repository.definitions.RepositoryDefinition.Source;
import build.jenesis.repository.gateway.RepositoryRouter.Outcome;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EPIC 29 RD-5 (stage 2): the two new fallback-clause grammar tokens the router grew - a {@code match=<ecosystem>:<glob>}
 * coordinate predicate that filters the fallback walk MISS-composably, and a {@code redirect} serve policy that
 * delegates a {@link Source.Upstream} leg to the injected {@link RepositoryRouter.RedirectHandler} (the
 * {@code redirect-directory} module) instead of fetch-screen-serving it. Proves, hermetically over a filesystem store
 * with no network:
 * <ul>
 *   <li>{@code match=} parses, and a coordinate that matches routes to that fallback while one that does not skips it
 *       and falls through to the next (MISS-composable), an unmatched coordinate exhausting the walk to a 404;</li>
 *   <li>{@code redirect} / {@code redirect unscreened} parse (only with a handler installed) and, on the upstream arm,
 *       emit the redirect through a stub handler that records the leg - never a fetch-screen-serve;</li>
 *   <li>a {@code redirect} token with no {@code redirect-directory} module installed is a fail-loud parse refusal
 *       naming the missing module (the {@code ArtifactStoreProvider.resolve} precedent), not a silent proxy;</li>
 *   <li>refusal-stops-the-walk still holds through the redirect arm (a REFUSED redirect never falls to a weaker
 *       fallback), and sibling-coherence holds (the leg is chosen once per request, so a coordinate-less checksum
 *       sibling follows the same leg its artifact took rather than being partitioned away).</li>
 * </ul>
 *
 * <p>These assertions are additive: the legacy routing/parse behavior stays proven by the unchanged
 * {@code RepositoryRouterTest}/{@code DefinitionParseTest}. Every {@code redirect} test flips the parse-availability
 * flag on for itself and {@link #resetRedirectAvailability() the @AfterEach resets it}, so the module-absent parse
 * refusal is asserted from the default (off) state regardless of ordering.
 */
public class RedirectFallbackTest {

    @TempDir
    Path root;

    @AfterEach
    public void resetRedirectAvailability() {
        // The redirect parse-availability is process-global (a module registers it at boot); reset after every test so
        // the module-absent refusal test sees the default (off) state independent of execution order.
        RepositoryDefinition.redirectHandlerInstalled(false);
        RepositoryDefinition.dnsDirectoryInstalled(false);
    }

    @Test
    public void a_store_or_screening_option_on_a_dns_fallback_is_refused_at_parse_not_a_class_cast() {
        // A DNS-directory leg emits a 307 and owns no store or screening policy, so a store/screening token on it is a
        // configuration error. It must be refused at parse with a clear IllegalArgumentException naming the option -
        // never reach the Source.Upstream cast in the 'unscreened' warning and throw a raw ClassCastException.
        RepositoryDefinition.redirectHandlerInstalled(true);
        RepositoryDefinition.dnsDirectoryInstalled(true);
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback dns redirect unscreened"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed on a 'dns' fallback");
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback dns redirect harden"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed on a 'dns' fallback");
    }

    // ---- match= : parse + MISS-composable routing -----------------------------------------------------------------

    @Test
    public void a_match_predicate_parses_onto_the_nearest_preceding_upstream_fallback() {
        RepositoryDefinition partitioned = RepositoryDefinition.parse(
                "fallback https://a/ match=test:com.foo.* fallback https://b/ match=test:com.bar.*");
        assertThat(partitioned.fallbacks()).hasSize(2);
        Fallback first = partitioned.fallbacks().get(0);
        Fallback second = partitioned.fallbacks().get(1);
        assertThat(first.match()).isEqualTo(new RepositoryDefinition.Match("test", "com.foo.*"));
        assertThat(first.serve()).as("match= is a walk filter, not a serve policy").isEqualTo(Serve.PROXY);
        assertThat(second.match()).isEqualTo(new RepositoryDefinition.Match("test", "com.bar.*"));

        // the predicate over a parsed coordinate: ecosystem case-insensitive, glob anchored, coordinate-less skipped
        assertThat(first.match().matches(descriptor("com.foo.lib", "1.0"))).isTrue();
        assertThat(first.match().matches(descriptor("com.bar.lib", "1.0"))).as("a different namespace").isFalse();
        assertThat(first.match().matches(ArtifactDescriptor.at("test", "/t/x.sha1")))
                .as("a coordinate-less descriptor never matches a predicate").isFalse();

        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback https://a/ match=nocolon"))
                .as("a match= with no ':' is refused at parse")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<ecosystem>:<glob>");
    }

    @Test
    public void a_matching_coordinate_routes_and_a_non_matching_one_skips_that_fallback() throws Exception {
        Fixture fixture = new Fixture(Map.of("partitioned", RepositoryDefinition.parse(
                "fallback https://a/ match=test:com.foo.* fallback https://b/ match=test:com.bar.*")));
        // Both upstreams can serve every coordinate, so a wrong-leg fetch would still 200 - the assertion is on WHICH
        // upstream served, which distinguishes a real skip from an incidental miss-then-fallthrough.

        // com.foo.* matches the FIRST fallback: served from A, and B is never consulted for it
        assertThat(fixture.get("partitioned", "/t/com.foo.lib/1.0/lib.jar")).isEqualTo("from a");
        assertThat(fixture.fetched).containsExactly("https://a/com.foo.lib/1.0/lib.jar");

        // com.bar.* does NOT match the first fallback (predicate skips it, MISS-composable) and falls through to the
        // second, which serves it - proving the skip, since A (which could also serve it) was never fetched
        fixture.fetched.clear();
        assertThat(fixture.get("partitioned", "/t/com.bar.lib/2.0/lib.jar")).isEqualTo("from b");
        assertThat(fixture.fetched).containsExactly("https://b/com.bar.lib/2.0/lib.jar");

        // a coordinate that matches neither predicate exhausts the walk to a 404 (no unfiltered leg to catch it)
        fixture.fetched.clear();
        assertThat(fixture.serve("partitioned", "/t/com.baz.lib/1.0/lib.jar").status).isEqualTo(404);
        assertThat(fixture.fetched).as("no fallback applied, so no upstream was fetched").isEmpty();
    }

    // ---- redirect : parse (only with a handler installed) + emission through the handler ---------------------------

    @Test
    public void redirect_and_redirect_unscreened_parse_only_with_a_handler_installed() {
        RepositoryDefinition.redirectHandlerInstalled(true);

        Fallback screened = only(RepositoryDefinition.parse("fallback https://cdn/ redirect"));
        assertThat(screened.serve()).isEqualTo(Serve.REDIRECT);
        assertThat(screened.screening()).as("a bare redirect keeps the screened-floor default").isEqualTo(Screening.DEFAULT);

        Fallback unscreened = only(RepositoryDefinition.parse("fallback https://cdn/ redirect unscreened"));
        assertThat(unscreened.serve()).isEqualTo(Serve.REDIRECT);
        assertThat(unscreened.screening()).as("redirect unscreened is the opt-in bookmark redirect")
                .isEqualTo(Screening.UNSCREENED);

        // match= and redirect compose on the same fallback
        Fallback both = only(RepositoryDefinition.parse("fallback https://cdn/ match=test:com.foo.* redirect"));
        assertThat(both.serve()).isEqualTo(Serve.REDIRECT);
        assertThat(both.match()).isEqualTo(new RepositoryDefinition.Match("test", "com.foo.*"));
    }

    @Test
    public void a_redirect_upstream_leg_emits_the_redirect_through_the_handler_never_a_fetch() throws Exception {
        RepositoryDefinition.redirectHandlerInstalled(true);
        RecordingRedirect handler = new RecordingRedirect(Outcome.HIT);
        Fixture fixture = new Fixture(Map.of("forward", RepositoryDefinition.parse("fallback https://cdn/ redirect")),
                router -> router.redirecting(handler));

        Fixture.TestExchange exchange = fixture.serve("forward", "/t/com.foo.lib/1.0/lib.jar");
        assertThat(exchange.status).as("the leg redirected").isEqualTo(307);
        assertThat(exchange.header("Location")).isEqualTo("https://cdn/t/com.foo.lib/1.0/lib.jar");
        assertThat(fixture.fetched).as("a redirect leg moves no bytes - fetchScreenServe was never reached").isEmpty();
        assertThat(handler.calls).hasSize(1);
        RecordingRedirect.Call call = handler.calls.getFirst();
        assertThat(call.repository).isEqualTo("forward");
        assertThat(call.upstream).isEqualTo(URI.create("https://cdn/"));
        assertThat(call.path).isEqualTo("/t/com.foo.lib/1.0/lib.jar");
        assertThat(call.screening).as("a bare redirect is served with the screened floor").isEqualTo(Screening.DEFAULT);
    }

    @Test
    public void a_redirect_unscreened_leg_carries_the_unscreened_screening_to_the_handler() throws Exception {
        RepositoryDefinition.redirectHandlerInstalled(true);
        RecordingRedirect handler = new RecordingRedirect(Outcome.HIT);
        Fixture fixture = new Fixture(Map.of("bookmark", RepositoryDefinition.parse("fallback https://cdn/ redirect unscreened")),
                router -> router.redirecting(handler));

        assertThat(fixture.serve("bookmark", "/t/com.foo.lib/1.0/lib.jar").status).isEqualTo(307);
        assertThat(handler.calls.getFirst().screening).as("the handler sees the unscreened opt-out on the fallback")
                .isEqualTo(Screening.UNSCREENED);
    }

    // ---- redirect with no module installed : fail-loud parse refusal ----------------------------------------------

    @Test
    public void a_redirect_token_with_no_module_installed_is_a_parse_refusal_naming_the_missing_module() {
        // The @AfterEach reset (and the default) leaves redirect availability OFF: a `redirect` serve token must then be
        // a fail-loud parse refusal naming the missing module, never a silent degrade to fetch-screen-serve (the
        // ArtifactStoreProvider.resolve store=s3-without-module precedent).
        assertThat(RepositoryDefinition.redirectHandlerInstalled()).as("no handler installed").isFalse();
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback https://cdn/ redirect"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect-directory")
                .hasMessageContaining("refused rather than silently proxied");
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback https://cdn/ redirect unscreened"))
                .as("the unscreened variant is refused too")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect-directory");
    }

    @Test
    public void a_redirect_on_a_repository_name_fallback_is_refused_regardless_of_the_module() {
        RepositoryDefinition.redirectHandlerInstalled(true);
        // A redirect emits a 307 to an upstream URL; a repository-name view has no URL to redirect to, so it is refused
        // whether or not the module is installed.
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback inner redirect"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository-name fallback");
    }

    // ---- refusal-stops-the-walk + sibling-coherence through the redirect arm --------------------------------------

    @Test
    public void a_refused_redirect_ends_the_walk_and_no_weaker_fallback_is_consulted() throws Exception {
        RepositoryDefinition.redirectHandlerInstalled(true);
        RecordingRedirect handler = new RecordingRedirect(Outcome.REFUSED);   // the redirect handler withholds
        // a redirect leg first, a plain caching proxy leg second: a REFUSED redirect must NOT fall through to the proxy
        Fixture fixture = new Fixture(Map.of("guarded",
                RepositoryDefinition.parse("fallback https://cdn/ redirect fallback https://b/")),
                router -> router.redirecting(handler));

        Fixture.TestExchange exchange = fixture.serve("guarded", "/t/com.foo.lib/1.0/lib.jar");
        assertThat(exchange.status).as("REFUSED is a non-disclosive 404 committed by resolve()").isEqualTo(404);
        assertThat(handler.calls).as("the redirect leg ran").hasSize(1);
        assertThat(fixture.fetched).as("the walk ended at the refusal - the weaker proxy leg was never consulted")
                .isEmpty();
    }

    @Test
    public void a_declined_redirect_falls_through_to_the_next_fallback() throws Exception {
        RepositoryDefinition.redirectHandlerInstalled(true);
        RecordingRedirect handler = new RecordingRedirect(Outcome.MISS);   // the handler declines (e.g. floor veto)
        Fixture fixture = new Fixture(Map.of("guarded",
                RepositoryDefinition.parse("fallback https://cdn/ redirect fallback https://b/")),
                router -> router.redirecting(handler));

        // a MISS from the redirect handler is a genuine decline: the walk continues to the caching proxy, which serves
        assertThat(fixture.get("guarded", "/t/com.foo.lib/1.0/lib.jar")).isEqualTo("from b");
        assertThat(handler.calls).hasSize(1);
        assertThat(fixture.fetched).containsExactly("https://b/com.foo.lib/1.0/lib.jar");
    }

    @Test
    public void a_coordinate_less_sibling_follows_the_same_leg_its_artifact_took() throws Exception {
        RepositoryDefinition.redirectHandlerInstalled(true);
        RecordingRedirect handler = new RecordingRedirect(Outcome.HIT);
        // a single match= redirect leg: the .jar carries a coordinate the predicate admits; its .sha1 sibling carries
        // no coordinate. Sibling-coherence (route at fallback level, never per-URL): the coordinate-less sibling is left
        // to the configured order rather than partitioned away, so it takes the SAME leg the artifact took.
        Fixture fixture = new Fixture(Map.of("forward",
                RepositoryDefinition.parse("fallback https://cdn/ match=test:com.foo.* redirect")),
                router -> router.redirecting(handler));

        assertThat(fixture.serve("forward", "/t/com.foo.lib/1.0/lib.jar").status).as("the artifact redirects").isEqualTo(307);
        assertThat(fixture.serve("forward", "/t/com.foo.lib/1.0/lib.jar.sha1").status)
                .as("its coordinate-less checksum sibling follows the same leg (not skipped)").isEqualTo(307);
        assertThat(handler.calls).hasSize(2);
        assertThat(handler.calls.get(0).path).isEqualTo("/t/com.foo.lib/1.0/lib.jar");
        assertThat(handler.calls.get(1).path).as("the sibling took the same redirect leg").isEqualTo("/t/com.foo.lib/1.0/lib.jar.sha1");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Fallback only(RepositoryDefinition definition) {
        assertThat(definition.fallbacks()).hasSize(1);
        return definition.fallbacks().getFirst();
    }

    private static ArtifactDescriptor descriptor(String coordinate, String version) {
        return new ArtifactDescriptor("test", coordinate, version, "/t/" + coordinate + "/" + version, null, false, null, -1L);
    }

    /** A stub {@link RepositoryRouter.RedirectHandler} that records each leg it was handed and emits a 307 for a HIT
     *  (so a test can assert the leg redirected rather than fetch-screen-served), or commits nothing for a MISS/REFUSED
     *  (so the walk's fall-through / refusal-stops-the-walk semantics are exercised through the redirect arm). */
    private static final class RecordingRedirect implements RepositoryRouter.RedirectHandler {

        private record Call(String repository, URI upstream, String path, Screening screening) {
        }

        private final Outcome outcome;
        private final List<Call> calls = new ArrayList<>();

        private RecordingRedirect(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Outcome redirect(String tenant, String repository, Fallback fallback, URI upstream,
                                RepositoryFormat format, FormatExchange exchange) throws IOException {
            calls.add(new Call(repository, upstream, exchange.path(), fallback.screening()));
            if (outcome == Outcome.HIT) {
                String base = upstream.toString();
                String target = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + exchange.path();
                exchange.setResponseHeader("Location", target);
                exchange.respond(307);   // committed to the real exchange, ends the walk with a HIT
            }
            // a MISS leaves the exchange untouched so the walk continues (or resolve() commits the 404); a REFUSED
            // likewise commits nothing here (resolve() answers the non-disclosive 404) but ends the walk.
            return outcome;
        }
    }

    /** A hermetic router over a filesystem store with a coordinate-describing test format and an in-memory fetcher that
     *  records which upstream URLs it was asked for, so a test can assert the leg a request took. */
    private final class Fixture {

        private final RepositoryRouter router;
        private final ArtifactStore store;
        private final List<String> fetched = new ArrayList<>();

        private Fixture(Map<String, RepositoryDefinition> definitions) throws IOException {
            this(definitions, UnaryOperator.identity());
        }

        private Fixture(Map<String, RepositoryDefinition> definitions, UnaryOperator<RepositoryRouter> customize) throws IOException {
            this.store = ArtifactStoreProvider.resolve("filesystem",
                    key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
            ProxyFormat.Fetcher.Buffered fetcher = (url, _) -> {
                fetched.add(url.toString());
                // both upstreams serve every /t/ coordinate, so the routed leg is proven by WHICH upstream was asked
                String host = url.getHost();
                return Optional.of(new ProxyFormat.Fetched(200,
                        ("from " + host).getBytes(StandardCharsets.UTF_8), Map.of()));
            };
            this.router = customize.apply(new RepositoryRouter(definitions::get,
                    (tenant, repository) -> store.scope(tenant).scope(repository), fetcher));
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

        /** A coordinate-describing proxy format: {@code /t/<coordinate>/<version>/<file>}; a {@code .sha1} sibling
         *  carries no coordinate (a checksum root), so it exercises the empty-coordinate / sibling-coherence path. A
         *  GET serves stored bytes, a miss proxies one upstream (mirrors the existing router-test formats). */
        private final class TestFormat implements RepositoryFormat, ProxyFormat, ArtifactLayout {

            @Override
            public String name() {
                return "t";
            }

            @Override
            public boolean handles(String path) {
                return path.startsWith("/t/");
            }

            @Override
            public String ecosystem() {
                return "test";
            }

            @Override
            public Optional<ArtifactDescriptor> describe(String path) {
                String rest = path.substring("/t/".length());
                String[] parts = rest.split("/");
                if (parts.length < 3 || parts[parts.length - 1].endsWith(".sha1")) {
                    return Optional.of(ArtifactDescriptor.at("test", path));   // a checksum / non-artifact: no coordinate
                }
                return Optional.of(new ArtifactDescriptor("test", parts[0], parts[1], path, null,
                        parts[1].endsWith("-SNAPSHOT"), null, -1L));
            }

            @Override
            public List<String> paths(String coordinate, String version, ArtifactStore store) {
                return List.of();
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

        /** A {@link FormatExchange} that records the response (status, headers, body) so a test can assert a 307 and
         *  its {@code Location} (mirrors the existing router-test exchange, plus header capture). */
        private final class TestExchange implements FormatExchange {

            private final String method;
            private final String path;
            private final byte[] request;
            private final Map<String, String> responseHeaders = new LinkedHashMap<>();
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

            private String header(String name) {
                return responseHeaders.get(name);
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
                responseHeaders.put(name, value);
            }

            @Override
            public OutputStream respond(int status, long contentLength) {
                this.status = status;
                this.response = new ByteArrayOutputStream();
                return response;
            }
        }
    }
}
