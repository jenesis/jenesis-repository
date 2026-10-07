package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code fallback discovered} leg over the Maven format, without the network: the domain a groupId reverses into
 * names its artifacts in its discovery file, and the leg fetches, keeps and serves them as an upstream leg does - a
 * template's file at its own address, checked against the checksum beside it, a root's under the request's own path,
 * the metadata a latest link names answered rather than fetched. A body that does not match its checksum is neither
 * served nor kept, a coordinate no domain names falls through to the next leg, a file the proposal refuses answers
 * {@code 502}, and the grammar takes {@code discovered} with every option an upstream takes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DiscoveredLegTest {

    private static final String RELEASES = "https://github.com/jenesis/jenesis/releases/download/v0.20.0/";

    private static final String JENESIS = """
            maven=https://github.com/jenesis/jenesis/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
            maven.latest=https://github.com/jenesis/jenesis/releases/latest/download/{artifactId}.pom
            maven.suffixes=none
            """;

    @TempDir
    Path root;

    private final Map<String, byte[]> upstream = new HashMap<>();
    private final List<String> fetched = new ArrayList<>();
    private final Map<URI, String> files = new HashMap<>();
    private final Map<URI, RepositoryDiscovery.Head> heads = new HashMap<>();
    private ArtifactStore store;
    private RepositoryRouter router;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        files.put(URI.create("https://jenesis.build/.well-known/java-repository.properties"), JENESIS);
        files.put(URI.create("https://example.com/.well-known/java-repository.properties"),
                "maven=https://maven.example.com/releases/\n");
        files.put(URI.create("https://broken.org/.well-known/java-repository.properties"),
                "maven=http://plain.broken.org/\n");
        ProxyFormat.Fetcher.Buffered fetcher = (url, _) -> {
            fetched.add(url.toString());
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };
        RepositoryDiscovery.Transport transport = new RepositoryDiscovery.Transport() {
            @Override
            public Optional<String> read(URI url, int most) {
                return Optional.ofNullable(files.get(url));
            }

            @Override
            public Optional<RepositoryDiscovery.Head> head(URI url) {
                return Optional.of(heads.getOrDefault(url, new RepositoryDiscovery.Head(404, Map.of())));
            }
        };
        Map<String, RepositoryDefinition> definitions = Map.of(
                "discovered", RepositoryDefinition.parse("fallback discovered"),
                "fallthrough", RepositoryDefinition.parse("fallback discovered fallback https://central/"));
        router = new RepositoryRouter(definitions::get,
                (tenant, repository) -> store.scope(tenant).scope(repository), fetcher)
                .discovering(new RepositoryDiscovery(transport, _ -> false, Duration.ofHours(1), Clock.systemUTC()));
    }

    @Test
    void a_template_file_is_fetched_at_its_own_address_checked_and_kept() throws Exception {
        byte[] jar = "the jenesis jar".getBytes(StandardCharsets.UTF_8);
        upstream.put(RELEASES + "build.jenesis-0.20.0.jar", jar);
        upstream.put(RELEASES + "build.jenesis-0.20.0.jar.sha256",
                (sha256(jar) + "  build.jenesis-0.20.0.jar\n").getBytes(StandardCharsets.US_ASCII));
        String path = "/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar";

        Exchange first = get("discovered", path);
        assertThat(first.status).isEqualTo(200);
        assertThat(first.text()).isEqualTo("the jenesis jar");
        assertThat(fetched).contains(RELEASES + "build.jenesis-0.20.0.jar",
                RELEASES + "build.jenesis-0.20.0.jar.sha256");
        assertThat(fetched).noneMatch(url -> url.contains("discovered.invalid"));

        int asked = fetched.size();
        assertThat(get("discovered", path).text()).isEqualTo("the jenesis jar");
        assertThat(fetched).as("kept, so served again without asking").hasSize(asked);
    }

    @Test
    void a_body_that_does_not_match_its_checksum_is_neither_served_nor_kept() throws Exception {
        upstream.put(RELEASES + "build.jenesis-0.20.0.jar", "tampered".getBytes(StandardCharsets.UTF_8));
        upstream.put(RELEASES + "build.jenesis-0.20.0.jar.sha256",
                sha256("genuine".getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII));
        String path = "/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar";

        Exchange exchange = new Exchange(path);
        try {
            router.serve("acme", "discovered", new MavenFormat(), exchange);
        } catch (IOException | UncheckedIOException expected) {
            // A failed read may surface as the exception the store's write raised.
        }
        assertThat(exchange.status).isNotEqualTo(200);
        assertThat(new Publication(store.scope("acme").scope("discovered")).located(path)).isEmpty();
    }

    @Test
    void the_metadata_a_latest_link_names_is_answered_and_a_root_relays_the_path() throws Exception {
        heads.put(URI.create("https://github.com/jenesis/jenesis/releases/latest/download/build.jenesis.pom"),
                new RepositoryDiscovery.Head(302, Map.of("Location",
                        "https://github.com/jenesis/jenesis/releases/download/v0.21.0/build.jenesis.pom")));
        assertThat(get("discovered", "/maven/build/jenesis/build.jenesis/maven-metadata.xml").text())
                .contains("<release>0.21.0</release>");

        upstream.put("https://maven.example.com/releases/com/example/lib/1.0/lib-1.0.pom",
                "<project/>".getBytes(StandardCharsets.UTF_8));
        assertThat(get("discovered", "/maven/com/example/lib/1.0/lib-1.0.pom").text()).isEqualTo("<project/>");
    }

    @Test
    void a_coordinate_no_domain_names_falls_through_to_the_next_leg() throws Exception {
        upstream.put("https://central/org/acme/lib/1.0/lib-1.0.jar", "central".getBytes(StandardCharsets.UTF_8));

        assertThat(get("fallthrough", "/maven/org/acme/lib/1.0/lib-1.0.jar").text()).isEqualTo("central");
    }

    @Test
    void a_file_the_proposal_refuses_answers_502() throws Exception {
        Exchange exchange = new Exchange("/maven/org/broken/lib/1.0/lib-1.0.jar");
        router.serve("acme", "fallthrough", new MavenFormat(), exchange);

        assertThat(exchange.status).as("refused, never read as absent and fallen through").isEqualTo(502);
    }

    @Test
    void the_grammar_takes_discovered_with_every_option_an_upstream_takes() {
        RepositoryDefinition definition = RepositoryDefinition.parse(
                "writable fallback discovered harden nocache match=maven:build.jenesis:* fallback https://central/");

        RepositoryDefinition.Fallback discovered = definition.fallbacks().getFirst();
        assertThat(discovered.source()).isInstanceOf(RepositoryDefinition.Source.Discovered.class);
        assertThat(discovered.store()).isFalse();
        assertThat(discovered.screening()).isEqualTo(RepositoryDefinition.Screening.HARDEN);
        assertThat(discovered.match()).isNotNull();
        assertThat(definition.harden()).isTrue();
        assertThat(RepositoryDefinition.parse("fallback discovered").fallbacks().getFirst().store())
                .as("kept by default, as an upstream's bytes are").isTrue();
        RepositoryDefinition.redirectHandlerInstalled(false);
        assertThatThrownBy(() -> RepositoryDefinition.parse("fallback discovered redirect"))
                .as("a redirect needs the module that emits it").isInstanceOf(IllegalArgumentException.class);
    }

    private Exchange get(String repository, String path) throws IOException {
        Exchange exchange = new Exchange(path);
        router.serve("acme", repository, new MavenFormat(), exchange);
        return exchange;
    }

    private static String sha256(byte[] body) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static final class Exchange implements DetachedExchange {

        private final String path;
        private int status = -1;
        private ByteArrayOutputStream body;

        private Exchange(String path) {
            this.path = path;
        }

        String text() {
            return body == null ? "" : body.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return "GET";
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
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            this.body = new ByteArrayOutputStream();
            return body;
        }
    }
}
