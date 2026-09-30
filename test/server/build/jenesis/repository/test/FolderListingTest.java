package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.server.EdgeHooks;
import build.jenesis.repository.server.FolderListingSettingsContributor;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.RoutedServing;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.Withheld;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A folder of a Maven repository answers a listing when the repository's {@code folder-listing} setting is on, and
 * a {@code 404} as it always has when it is off, which is the shipped default: the listing names what a {@code GET}
 * would serve and nothing withheld, a page at a time with a link to the next, as HTML or, asked for, as JSON.
 */
class FolderListingTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void repository() throws IOException {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        new RepositoryDocument("maven", Instant.now()).create(store);
    }

    private String publish(String path, String content) throws IOException {
        return MavenFormat.layout(store, "/maven" + path,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_folder_lists_what_a_get_would_serve_and_nothing_withheld() throws Exception {
        publish("/com/acme/lib/1.0/lib-1.0.pom", "pom");
        publish("/com/acme/lib/1.0/lib-1.0.jar", "jar");
        Withheld.mark(store, publish("/com/acme/lib/1.0/lib-1.0-sources.jar", "held sources"));

        Answer group = get("/com/acme/", true, null, null);
        assertThat(group.status).isEqualTo(200);
        assertThat(group.body).contains("<a href=\"lib/\">lib/</a>").doesNotContain("?after=");

        Answer version = get("/com/acme/lib/1.0/", true, null, null);
        assertThat(version.body).contains("<a href=\"lib-1.0.pom\">lib-1.0.pom</a>")
                .contains("<a href=\"lib-1.0.jar\">lib-1.0.jar</a>")
                .as("a withheld file is not offered, since its download would fail").doesNotContain("sources");

        Answer json = get("/com/acme/lib/1.0/", true, null, "application/json");
        assertThat(json.body).isEqualTo("{\"entries\":[{\"name\":\"lib-1.0.jar\",\"folder\":false},"
                + "{\"name\":\"lib-1.0.pom\",\"folder\":false}],\"next\":null}");

        assertThat(get("/com/absent/", true, null, null).status).as("a folder with nothing in it").isEqualTo(404);
        assertThat(get("/com/acme/", true, "a/b", null).status).as("a cursor that is not a name").isEqualTo(400);
    }

    @Test
    void a_folder_holding_more_than_a_page_continues_on_the_next() throws Exception {
        for (int index = 0; index <= 1_000; index++) {
            publish("/com/many/lib/" + index + "/lib-" + index + ".pom", "pom " + index);
        }
        Answer first = get("/com/many/lib/", true, null, "application/json");
        List<String> names = names(first.body);
        assertThat(names).hasSize(1_000);
        String next = first.body.substring(first.body.indexOf("\"next\":\"") + 8, first.body.lastIndexOf('"'));
        assertThat(next).isEqualTo(names.getLast());
        assertThat(get("/com/many/lib/", true, null, null).body)
                .contains("<a rel=\"next\" href=\"?after=" + next + "\">");

        Answer rest = get("/com/many/lib/", true, next, "application/json");
        assertThat(names(rest.body)).hasSize(1).doesNotContainAnyElementsOf(names);
        assertThat(rest.body).endsWith("\"next\":null}");
    }

    @Test
    void the_listing_is_off_unless_asked_for() throws Exception {
        publish("/com/acme/lib/1.0/lib-1.0.pom", "pom");
        Setting declared = new FolderListingSettingsContributor().settings().stream()
                .filter(setting -> setting.key().equals("folder-listing")).findFirst().orElseThrow();
        assertThat(declared.defaultValue()).as("what the catalogue declares").isEqualTo("false");
        assertThat(declared.scope()).as("set per repository, inheriting the tenant's and the deployment's")
                .isEqualTo(Setting.Scope.REPOSITORY);
        assertThat(get("/com/acme/lib/", false, null, null).status)
                .as("what a repository with nothing set answers").isEqualTo(404);
    }

    private static List<String> names(String document) {
        List<String> names = new ArrayList<>();
        Matcher name = Pattern.compile("\"name\":\"([^\"]*)\"").matcher(document);
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    private record Answer(int status, String body) {
    }

    private Answer get(String folder, boolean listing, String after, String accept) throws Exception {
        RepositoryRouting routing = request -> new RepositoryRouting.Route("default", "releases", store, "/maven" + folder,
                false);
        RepositoryController.RepositorySettings settings = (tenant, repository, key) -> listing && tenant.equals("default")
                && repository.equals("releases") && key.equals("folder-listing") ? "true" : null;
        RepositoryController controller = new RepositoryController(routing,
                new FormatDispatcher(List.of(new MavenFormat()), Map.of(), ProxyFormat.Fetcher.NONE), List.of(),
                ProxyFormat.Fetcher.NONE, null, settings, null, RoutedServing.NONE, EdgeHooks.NONE, AuditTrail.NONE,
                RepositoryController.Reads.NONE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> attributes = new HashMap<>();
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/repository/default/releases/maven" + folder);
        when(request.getParameter("after")).thenReturn(after);
        when(request.getHeader("Accept")).thenReturn(accept);
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.<String>getArgument(0)));
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1))).when(request)
                .setAttribute(anyString(), any());
        HttpServletResponse response = mock(HttpServletResponse.class);
        int[] status = {200};
        doAnswer(call -> status[0] = call.<Integer>getArgument(0)).when(response).setStatus(anyInt());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }

            @Override
            public void write(int one) {
                body.write(one);
            }
        });
        controller.handle(request, response);
        return new Answer(status[0], body.toString(StandardCharsets.UTF_8));
    }
}
