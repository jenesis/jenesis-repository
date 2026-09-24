package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.EdgeHooks;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryPresence;
import build.jenesis.repository.server.RepositoryPresenceSettingsContributor;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.RoutedServing;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A repository nobody created does not answer unless a publish may create it. The default is the half a deployment
 * depends on, so it is asserted twice - what the catalogue declares, and what the code does with nothing set - and the
 * ways a repository comes to exist are each shown to count: content, a definition, the creation marker, and the
 * repository the routing serves without being asked.
 */
class RepositoryPresenceTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void by_default_a_publish_does_not_create_the_repository_it_names() throws IOException {
        Setting declared = new RepositoryPresenceSettingsContributor().settings().stream()
                .filter(setting -> setting.key().equals(RepositoryPresence.SETTING)).findFirst().orElseThrow();
        assertThat(declared.defaultValue()).as("the catalogue declares the default off").isEqualTo("false");
        assertThat(new RepositoryPresence(store, _ -> null, _ -> false).answers("acme", "releases"))
                .as("with nothing set, a repository nobody created does not answer").isFalse();
    }

    @Test
    void the_setting_lets_any_repository_answer() throws IOException {
        assertThat(new RepositoryPresence(store, settings(RepositoryPresence.SETTING, "true"), _ -> false)
                .answers("acme", "anything")).isTrue();
        assertThat(new RepositoryPresence(store, settings(RepositoryPresence.SETTING, "false"), _ -> false)
                .answers("acme", "anything")).isFalse();
    }

    @Test
    void a_repository_exists_by_content_by_definition_or_by_being_created() throws IOException {
        RepositoryPresence presence = new RepositoryPresence(store, _ -> null, "proxied"::equals);
        store.scope("acme").scope("used").write("raw/notes.txt", new ByteArrayInputStream("notes".getBytes(UTF_8)));
        assertThat(presence.answers("acme", "used")).as("a repository holding anything - every one a deployment "
                + "used before repositories were created deliberately").isTrue();
        assertThat(presence.answers("acme", "proxied")).as("a defined repository").isTrue();
        assertThat(presence.answers("acme", "created")).as("not created yet").isFalse();
        store.scope("acme").scope("created").write(Scopes.CREATED, new ByteArrayInputStream("now".getBytes(UTF_8)));
        assertThat(presence.answers("acme", "created"))
                .as("created since the tenant's listing was cached, so the direct probe finds it").isTrue();
        assertThat(presence.answers("globex", "used")).as("a repository is a tenant's own").isFalse();
    }

    @Test
    void a_publish_into_a_repository_nobody_created_is_refused_with_the_way_to_create_it() throws Exception {
        Response refused = handle("PUT", "absent", false);
        assertThat(refused.status).isEqualTo(404);
        assertThat(refused.body.toString()).contains("'absent' does not exist").contains(RepositoryPresence.SETTING);

        Response read = handle("GET", "absent", false);
        assertThat(read.status).as("a read is refused too - a pull-through upstream would fill the repository")
                .isEqualTo(404);
        assertThat(read.body.toString()).as("with nothing to say to a client that only asked").isEmpty();
    }

    @Test
    void the_repository_the_routing_serves_answers_without_being_created() throws Exception {
        Response served = handle("PUT", "releases", true);
        assertThat(served.body.toString()).as("past the presence check; the empty format set leaves it an unclaimed "
                + "404 with nothing written").isEmpty();
    }

    private Response handle(String method, String repository, boolean served) throws Exception {
        RepositoryRouting.Route route = new RepositoryRouting.Route("acme", repository, store, "/raw/x.txt");
        RepositoryRouting routing = new RepositoryRouting() {
            @Override
            public Route route(HttpServletRequest request) {
                return route;
            }

            @Override
            public boolean serves(String tenant, String name) {
                return served;
            }
        };
        RepositoryController controller = new RepositoryController(routing,
                new FormatDispatcher(List.of(), Map.of(), ProxyFormat.Fetcher.NONE), List.of(),
                ProxyFormat.Fetcher.NONE, null, _ -> null, store, RoutedServing.NONE, EdgeHooks.NONE,
                new RepositoryPresence(store, _ -> null, _ -> false));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        Response response = new Response();
        HttpServletResponse servlet = mock(HttpServletResponse.class);
        doAnswer(invocation -> response.status = invocation.getArgument(0)).when(servlet).setStatus(anyInt());
        when(servlet.getWriter()).thenReturn(new PrintWriter(response.body));
        controller.handle(request, servlet);
        return response;
    }

    private static UnaryOperator<String> settings(String key, String value) {
        return name -> name.equals(key) ? value : null;
    }

    private static final class Response {
        int status = -1;
        final StringWriter body = new StringWriter();
    }
}
