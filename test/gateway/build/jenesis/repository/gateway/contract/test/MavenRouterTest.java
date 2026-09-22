package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a Maven group resolves a build through the {@link RepositoryRouter} with the
 * {@link MavenFormat}, without the network: a group over a hosted member and a proxy of a fixed
 * upstream serves a first-party POM from the hosted member and a third-party jar from the proxy (caching it),
 * behind one repository - the single front door a {@code mvn} build would point at.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MavenRouterTest {

    private static final String POM = "/maven/com/acme/app/1.0/app-1.0.pom";
    private static final String JAR = "/maven/org/lib/dep/2.0/dep-2.0.jar";

    @TempDir
    static Path root;

    private RepositoryRouter router;
    private AtomicInteger fetches;

    @BeforeAll
    public void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Map<String, byte[]> upstream = Map.of("http://central/org/lib/dep/2.0/dep-2.0.jar",
                "the dependency jar".getBytes(StandardCharsets.UTF_8));
        fetches = new AtomicInteger();
        ProxyFormat.Fetcher.Buffered fetcher = (url, _) -> {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };
        Map<String, RepositoryDefinition> definitions = Map.of(
                "releases", RepositoryDefinition.parse("hosted"),
                "central", RepositoryDefinition.parse("proxy http://central/"),
                "public", RepositoryDefinition.parse("group releases,central"));
        router = new RepositoryRouter(definitions::get,
                (tenant, repository) -> store.scope(tenant).scope(repository), fetcher);

        // a first-party POM is deployed to the hosted member
        new MavenFormat().handle(new MavenExchange("PUT", POM, "the app pom".getBytes(StandardCharsets.UTF_8)),
                store.scope("acme").scope("releases"));
    }

    @Test
    public void a_group_serves_first_party_locally_and_third_party_from_the_proxy() throws Exception {
        // the first-party POM is served from the hosted member of the group
        assertThat(get("public", POM)).isEqualTo("the app pom");

        // a third-party jar misses the hosted member and is fetched and cached by the proxy member
        assertThat(get("public", JAR)).isEqualTo("the dependency jar");
        assertThat(fetches.get())
                .as("the jar, its upstream checksum probe, and the two companions the pull-through asks for beside "
                        + "it - the .asc and the .sigstore.json Central publishes - once")
                .isEqualTo(4);
        assertThat(get("central", JAR)).as("the proxy cached the jar").isEqualTo("the dependency jar");
        assertThat(fetches.get()).as("served from the cache").isEqualTo(4);

        assertThat(router.writeTarget("public")).as("a group is read-only (EPIC 25 §2.3, no push-delegation)").isNull();
    }

    private String get(String repository, String path) throws IOException {
        MavenExchange exchange = new MavenExchange("GET", path, new byte[0]);
        router.serve("acme", repository, new MavenFormat(), exchange);
        assertThat(exchange.status).as("GET " + repository + path).isEqualTo(200);
        return new String(exchange.body == null ? new byte[0] : exchange.body.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class MavenExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] request;
        private int status = -1;
        private ByteArrayOutputStream body;

        private MavenExchange(String method, String path, byte[] request) {
            this.method = method;
            this.path = path;
            this.request = request;
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
            this.body = new ByteArrayOutputStream();
            return body;
        }
    }
}
