package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.EdgeHooks;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.RoutedServing;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the edge answers a format that reads another repository on its caller's behalf - a registry's cross-repository
 * blob mount ({@code FormatExchange.readable}): that repository's store when the caller may read the path and it is a
 * repository of the request's tenant holding the same format, and nothing otherwise. Whether the caller may read it is
 * asked before anything about the other repository is looked at, so every refusal is the same empty answer and the
 * question is never a probe.
 */
class EdgeReadableTest {

    @TempDir
    Path root;

    private ArtifactStore tenant;
    private final List<String> asked = new ArrayList<>();

    /** A format that, on a POST, asks the edge for the path the request's body names and records the answer. */
    private static final class Asking implements RepositoryFormat {

        private final String name;
        private Optional<ArtifactStore> answer;

        private Asking(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/" + name + "/");
        }

        /** Unscreened, as the registry is: the request reaches the format as the edge built it. */
        @Override
        public boolean screened() {
            return false;
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            answer = exchange.readable(new String(exchange.requestStream().readAllBytes(), StandardCharsets.UTF_8));
            exchange.respond(202);
        }
    }

    /** A routing double answering the request's own route and any other repository of the tenant by name. */
    private final class Routing implements RepositoryRouting {

        @Override
        public Route route(HttpServletRequest request) {
            return new Route("acme", "images", tenant.scope("images"), "/asking/upload", true);
        }

        @Override
        public Optional<Route> route(String tenantName, String repository, String path) {
            return Optional.of(new Route(tenantName, repository, tenant.scope(repository), path, true));
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        tenant = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme");
        new RepositoryDocument("asking", Instant.now()).create(tenant.scope("images"));
        new RepositoryDocument("asking", Instant.now()).create(tenant.scope("base"));
        new RepositoryDocument("other", Instant.now()).create(tenant.scope("elsewhere"));
    }

    @Test
    void a_repository_the_caller_may_read_is_answered_with_its_store() throws Exception {
        tenant.scope("base").write("marker", new ByteArrayInputStream(new byte[] {1}));
        Optional<ArtifactStore> answer = ask("/repository/acme/base/asking/blob", true);
        assertThat(answer).as("the other repository's own store").hasValueSatisfying(store ->
                assertThat(store.exists("marker")).isTrue());
        assertThat(asked).containsExactly("/repository/acme/base/asking/blob");
    }

    @Test
    void every_refusal_is_the_same_empty_answer() throws Exception {
        assertThat(ask("/repository/acme/base/asking/blob", false))
                .as("a repository the caller may not read").isEmpty();
        assertThat(ask("/repository/acme/absent/asking/blob", false))
                .as("one that does not exist, asked the same way").isEmpty();
        assertThat(asked).as("both were asked of the authorization before anything was looked up")
                .containsExactly("/repository/acme/base/asking/blob", "/repository/acme/absent/asking/blob");
        asked.clear();

        assertThat(ask("/repository/acme/absent/asking/blob", true)).as("permitted, and absent").isEmpty();
        assertThat(ask("/repository/acme/elsewhere/asking/blob", true)).as("permitted, another format").isEmpty();
        assertThat(ask("/repository/other/base/asking/blob", true)).as("another tenant is never read").isEmpty();
        assertThat(asked).as("another tenant is refused without asking")
                .containsExactly("/repository/acme/absent/asking/blob", "/repository/acme/elsewhere/asking/blob");
    }

    private Optional<ArtifactStore> ask(String path, boolean permitted) throws Exception {
        Asking format = new Asking("asking");
        FormatDispatcher formats = new FormatDispatcher(List.of(format, new Asking("other")), Map.of(),
                ProxyFormat.Fetcher.NONE);
        RepositoryController controller = new RepositoryController(new Routing(), formats, List.of(),
                ProxyFormat.Fetcher.NONE, null, key -> null, null, RoutedServing.NONE, EdgeHooks.NONE,
                AuditTrail.NONE, (_, requested) -> {
                    asked.add(requested);
                    return permitted;
                });
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getInputStream()).thenReturn(new Body(path.getBytes(StandardCharsets.UTF_8)));
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(new Discarded());
        controller.handle(request, response);
        return format.answer;
    }

    /** A request body. */
    private static final class Body extends jakarta.servlet.ServletInputStream {

        private final ByteArrayInputStream in;

        private Body(byte[] content) {
            this.in = new ByteArrayInputStream(content);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener listener) {
        }
    }

    /** A response body nobody reads. */
    private static final class Discarded extends ServletOutputStream {

        @Override
        public void write(int b) {
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
        }
    }
}
