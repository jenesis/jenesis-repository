package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.definitions.RepositoryDefinition.Fallback;
import build.jenesis.repository.definitions.RepositoryDefinition.Serve;
import build.jenesis.repository.definitions.RepositoryDefinition.Source;
import build.jenesis.repository.gateway.RepositoryRouter.Outcome;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EPIC 30 DF-6 (design §7.2): the {@code dns} source keyword the RD-5 grammar grew - a third {@link Source} variant,
 * {@link Source.DnsDirectory}, that makes DNS forwarding definition-native. Proves, hermetically over the gateway with
 * no network and no {@code redirect-dns} module on the classpath (the DNS routing is stubbed at the
 * {@link RepositoryRouter.RedirectHandler} seam):
 * <ul>
 *   <li>{@code fallback dns redirect} and {@code writable fallback dns redirect} (the §5.2 storage-backed shape) parse
 *       to a {@link Source.DnsDirectory} leg served as a {@link Serve#REDIRECT} - only with the module installed;</li>
 *   <li>the keyword {@code dns} takes precedence over a repository literally named {@code dns}: a bare {@code fallback
 *       dns} (the reserved-keyword collision) is a fail-loud rename-ask refusal, consistent with the grammar's other
 *       refusals;</li>
 *   <li>a {@code dns} token with the {@code redirect-dns} module absent is a parse-time refusal naming the missing
 *       module (mirroring the {@code redirect}-token missing-module precedent), never a silent repository named
 *       {@code dns};</li>
 *   <li>the walk arm delegates a {@link Source.DnsDirectory} leg to the injected {@link RepositoryRouter.RedirectHandler}
 *       with a {@code null} upstream (the target is resolved by the walk, not a clause literal) - a HIT emits the 307
 *       and ends the walk, a MISS falls through / 404s (refusal-stops-the-walk inherited unchanged).</li>
 * </ul>
 *
 * <p>The real {@code DnsDirectory.locate} delegation (a secure record routing, the maven/jenesis gate and the
 * DNSSEC-required fail-closed posture all holding through the {@code DnsRedirectHandler})
 * is proven in the {@code redirect-dns} test module, which has the DNS units on its path; here the seam is stubbed so
 * the gateway grammar and walk arm are proven without pulling the DNS library into the gateway suite.
 *
 * <p>Every test flips {@link RepositoryDefinition#dnsDirectoryInstalled(boolean)} on for itself; {@link #reset()} resets it
 * (and the redirect availability) so the module-absent refusal is asserted from the default (off) state independent of
 * execution order.
 */
public class DnsSourceTest {

    @AfterEach
    public void reset() {
        RepositoryDefinition.dnsDirectoryInstalled(false);
        RepositoryDefinition.redirectHandlerInstalled(false);
    }

    // ---- parse : the dns source keyword ---------------------------------------------------------------------------

    @Test
    public void fallback_dns_redirect_parses_to_a_dns_directory_source_served_as_a_redirect() {
        RepositoryDefinition.dnsDirectoryInstalled(true);

        RepositoryDefinition pureRouter = RepositoryDefinition.parse("fallback dns redirect");
        assertThat(pureRouter.writable()).as("a pure router owns no storage").isFalse();
        assertThat(pureRouter.fallbacks()).hasSize(1);
        Fallback dns = pureRouter.fallbacks().getFirst();
        assertThat(dns.source()).as("the dns keyword is the third Source variant").isInstanceOf(Source.DnsDirectory.class);
        assertThat(dns.serve()).as("a dns leg is served only as a redirect").isEqualTo(Serve.REDIRECT);
        assertThat(dns.store()).as("a dns leg stores nothing of its own (the target owns its bytes)").isFalse();
    }

    @Test
    public void writable_fallback_dns_redirect_parses_to_the_storage_backed_shape() {
        RepositoryDefinition.dnsDirectoryInstalled(true);

        RepositoryDefinition storageBacked = RepositoryDefinition.parse("writable fallback dns redirect");
        assertThat(storageBacked.writable()).as("the §5.2 storage-backed shape: local store first, DNS on miss").isTrue();
        assertThat(storageBacked.fallbacks()).hasSize(1);
        assertThat(storageBacked.fallbacks().getFirst().source()).isInstanceOf(Source.DnsDirectory.class);
        assertThat(storageBacked.fallbacks().getFirst().serve()).isEqualTo(Serve.REDIRECT);
    }

    @Test
    public void a_repository_literally_named_dns_collides_with_the_keyword_and_is_a_rename_ask_refusal() {
        RepositoryDefinition.dnsDirectoryInstalled(true);
        // The keyword `dns` takes precedence, so `fallback dns` (a bare repository-name-shaped fallback, no `redirect`
        // serve) is the collision: refused as a fail-loud rename ask, never silently taken as a repository named `dns`
        // nor constructed as a DNS leg the walk could not serve.
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback dns"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved")
                .hasMessageContaining("rename");
    }

    @Test
    public void a_dns_token_with_the_module_absent_is_a_parse_refusal_naming_the_missing_module() {
        // The @AfterEach reset (and the default) leaves the redirect-dns module OFF: a `dns` source keyword must then be
        // a fail-loud parse refusal naming redirect-dns, never a silent repository named `dns`.
        assertThat(RepositoryDefinition.dnsDirectoryInstalled()).as("no redirect-dns module installed").isFalse();
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback dns redirect"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect-dns")
                .hasMessageContaining("refused rather than silently taken as a repository");
        assertThatThrownBy(() -> RepositoryDefinition.parse("writable fallback dns redirect"))
                .as("the storage-backed shape is refused too")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect-dns");
    }

    @Test
    public void a_dns_leg_composes_with_a_match_predicate() {
        RepositoryDefinition.dnsDirectoryInstalled(true);
        RepositoryDefinition partitioned = RepositoryDefinition.parse("fallback dns match=maven:com.foo.* redirect");
        Fallback dns = partitioned.fallbacks().getFirst();
        assertThat(dns.source()).isInstanceOf(Source.DnsDirectory.class);
        assertThat(dns.serve()).isEqualTo(Serve.REDIRECT);
        assertThat(dns.match()).isEqualTo(new RepositoryDefinition.Match("maven", "com.foo.*"));
    }

    // ---- walk arm : delegate a dns leg to the RedirectHandler with a null upstream --------------------------------

    @Test
    public void the_walk_delegates_a_dns_leg_to_the_handler_with_a_null_upstream_and_emits_the_307() throws Exception {
        RepositoryDefinition.dnsDirectoryInstalled(true);
        // The stub stands in for the redirect-dns handler: on a HIT it composes target ∘ residual-path and 307s.
        RecordingDnsRedirect handler = new RecordingDnsRedirect(Outcome.HIT, URI.create("https://repo.acme/"));
        RepositoryRouter router = new RepositoryRouter(
                name -> "forward".equals(name) ? RepositoryDefinition.parse("fallback dns redirect") : null,
                (tenant, repository) -> null,   // a pure router owns no store: stores are never consulted
                ProxyFormat.Fetcher.NONE)   // a dns leg moves no bytes: the fetcher is never called
                .redirecting(handler);

        RecordingExchange exchange = new RecordingExchange("GET", "/maven/com/acme/lib/1.0/lib-1.0.jar");
        router.serve("acme", "forward", new MavenishFormat(), exchange);

        assertThat(exchange.status).as("the dns leg redirected").isEqualTo(307);
        assertThat(exchange.header("Location")).isEqualTo("https://repo.acme/maven/com/acme/lib/1.0/lib-1.0.jar");
        assertThat(handler.calls).hasSize(1);
        RecordingDnsRedirect.Call call = handler.calls.getFirst();
        assertThat(call.upstream).as("a dns leg carries no clause-literal upstream (§7.2)").isNull();
        assertThat(call.source).as("the handler sees the DnsDirectory source").isInstanceOf(Source.DnsDirectory.class);
        assertThat(call.path).isEqualTo("/maven/com/acme/lib/1.0/lib-1.0.jar");
    }

    @Test
    public void a_dns_leg_that_the_handler_misses_falls_through_to_a_404_on_a_pure_router() throws Exception {
        RepositoryDefinition.dnsDirectoryInstalled(true);
        // A MISS from the handler (DNS did not route: no record, gated, or DNSSEC fail-closed) is a genuine decline; on a
        // pure router there is nothing to fall through to, so the walk exhausts to a non-disclosive 404.
        RecordingDnsRedirect handler = new RecordingDnsRedirect(Outcome.MISS, null);
        RepositoryRouter router = new RepositoryRouter(
                name -> RepositoryDefinition.parse("fallback dns redirect"),
                (tenant, repository) -> null, ProxyFormat.Fetcher.NONE)
                .redirecting(handler);

        RecordingExchange exchange = new RecordingExchange("GET", "/maven/com/acme/lib/1.0/lib-1.0.jar");
        Outcome outcome = router.resolve("acme", "forward", new MavenishFormat(), exchange);
        assertThat(outcome).isEqualTo(Outcome.MISS);
        assertThat(exchange.status).as("an unrouted coordinate 404s").isEqualTo(404);
        assertThat(handler.calls).as("the dns leg was consulted").hasSize(1);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A stub {@link RepositoryRouter.RedirectHandler} for the DNS leg: it records each call (so a test can assert the
     *  {@code null} upstream and the {@link Source.DnsDirectory} source) and, on a HIT, composes the target with the
     *  residual path and 307s - standing in for the real {@code DnsRedirectHandler} + DNS walk without the DNS units. */
    private static final class RecordingDnsRedirect implements RepositoryRouter.RedirectHandler {

        private record Call(URI upstream, Source source, String path) {
        }

        private final Outcome outcome;
        private final URI target;
        private final List<Call> calls = new ArrayList<>();

        private RecordingDnsRedirect(Outcome outcome, URI target) {
            this.outcome = outcome;
            this.target = target;
        }

        @Override
        public Outcome redirect(String tenant, String repository, Fallback fallback, URI upstream,
                                RepositoryFormat format, FormatExchange exchange) throws IOException {
            calls.add(new Call(upstream, fallback.source(), exchange.path()));
            if (outcome == Outcome.HIT) {
                String base = target.toString();
                String location = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + exchange.path();
                exchange.setResponseHeader("Location", location);
                exchange.respond(307);
            }
            return outcome;
        }
    }

    /** A minimal coordinate-describing Maven-ish format: {@code /maven/<group-path>/<artifact>/<version>/<file>} ->
     *  {@code Maven} descriptor with a {@code group:artifact} coordinate, enough for the router to hand the dns leg a
     *  described request. It never serves bytes itself - a pure-router dns leg redirects. */
    private static final class MavenishFormat implements RepositoryFormat, ArtifactLayout {

        @Override
        public String name() {
            return "maven";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/maven/");
        }

        @Override
        public String ecosystem() {
            return "Maven";
        }

        @Override
        public Optional<ArtifactDescriptor> describe(String path) {
            String rest = path.substring("/maven/".length());
            String[] parts = rest.split("/");
            if (parts.length < 4) {
                return Optional.of(ArtifactDescriptor.at("Maven", path));   // a checksum / non-artifact: no coordinate
            }
            String version = parts[parts.length - 2];
            String artifact = parts[parts.length - 3];
            String group = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 3));
            return Optional.of(new ArtifactDescriptor("Maven", group + ":" + artifact, version, path, null,
                    version.endsWith("-SNAPSHOT"), null, -1L));
        }

        @Override
        public List<String> paths(String coordinate, String version, ArtifactStore store) {
            return List.of();
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            exchange.respond(404);   // a pure router holds no local bytes
        }
    }

    /** A {@link FormatExchange} that records the response status and headers so a test can assert a 307 and its
     *  {@code Location} (the RedirectFallbackTest exchange, trimmed to what a dns-leg test asserts). */
    private static final class RecordingExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private int status = -1;
        private ByteArrayOutputStream response;

        private RecordingExchange(String method, String path) {
            this.method = method;
            this.path = path;
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
            return new ByteArrayInputStream(new byte[0]);
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

