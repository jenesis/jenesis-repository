package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.console.api.BrowseChildrenController;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/browse/children}, the API twin of the console's folder screen, driven in process over a real store:
 * a folder is paged by cursor rather than listed whole, each child is marked folder or artifact, a path that names no
 * folder is a {@code 404} rather than an empty folder, and a cursor that is not one child's name is refused - and the
 * children it answers for a folder are the ones the console's folder screen answers, since both read one
 * implementation.
 */
class BrowseChildrenControllerTest {

    private static final String TENANT = "acme";
    private static final String REPOSITORY = "files";

    @TempDir
    Path root;

    private ArtifactStore store;
    private BrowseChildrenController controller;

    @BeforeEach
    void wire() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        Repositories repositories = new Repositories(store, Authorization.anonymous(), live, Optional.empty(),
                Optional.empty());
        // The routing answers the one tenant: which tenant a request may name is the routing's question, not this one.
        RepositoryRouting routing = _ -> new RepositoryRouting.Route(TENANT, REPOSITORY, repository(), "/", false);
        controller = new BrowseChildrenController(repositories, routing);
        for (String path : List.of("/docs/a.txt", "/docs/b.txt", "/docs/c.txt", "/docs/guide/readme.txt")) {
            Publication publication = new Publication(repository());
            publication.link(path,
                    publication.storeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private ArtifactStore repository() {
        return store.scope(TENANT).scope(REPOSITORY);
    }

    private BrowseChildrenController.ChildrenView children(String prefix, String after, Integer limit,
                                                           Servlets.Response response) throws IOException {
        return controller.children(REPOSITORY, prefix, after, limit,
                Servlets.request("GET", "/api/browse/children"), response.servlet());
    }

    @Test
    void a_folder_is_paged_by_cursor_and_each_child_says_what_it_is() throws IOException {
        Servlets.Response response = Servlets.response();

        BrowseChildrenController.ChildrenView first = children("/docs", null, 2, response);
        BrowseChildrenController.ChildrenView second = children("/docs", first.next(), 2, response);

        assertThat(response.status()).isEqualTo(200);
        assertThat(first.prefix()).isEqualTo("/docs");
        assertThat(first.children()).extracting(BrowseChildrenController.Child::name).containsExactly("a.txt", "b.txt");
        assertThat(first.next()).as("a cut-short window names the child the next one resumes after").isEqualTo("b.txt");
        assertThat(second.children()).extracting(BrowseChildrenController.Child::name)
                .containsExactly("c.txt", "guide");
        assertThat(second.next()).as("and the last window says the folder is drained").isNull();
        assertThat(second.children()).filteredOn(BrowseChildrenController.Child::folder)
                .singleElement().satisfies(folder -> assertThat(folder.path()).isEqualTo("/docs/guide"));
        assertThat(first.children().getFirst().bytes()).as("a leaf's recorded size")
                .isEqualTo("/docs/a.txt".length());
    }

    @Test
    void a_path_that_names_no_folder_is_not_found_and_an_empty_root_is_an_empty_window() throws IOException {
        Servlets.Response missing = Servlets.response();
        assertThat(children("/nowhere", null, null, missing)).isNull();
        assertThat(missing.status()).as("a mistyped path is not an empty folder").isEqualTo(404);

        Servlets.Response empty = Servlets.response();
        RepositoryRouting routing = _ -> new RepositoryRouting.Route(TENANT, "empty",
                store.scope(TENANT).scope("empty"), "/", false);
        LiveConfig live = new LiveConfig(new Settings(store), new RepositoryProperties(), AdvisorySource.none(),
                _ -> null);
        BrowseChildrenController.ChildrenView view = new BrowseChildrenController(
                new Repositories(store, Authorization.anonymous(), live, Optional.empty(), Optional.empty()), routing)
                .children("empty", "", null, null, Servlets.request("GET", "/api/browse/children"), empty.servlet());
        assertThat(empty.status()).isEqualTo(200);
        assertThat(view.children()).isEmpty();
        assertThat(view.next()).isNull();
    }

    @Test
    void a_cursor_that_is_not_one_childs_name_is_refused() throws IOException {
        Servlets.Response response = Servlets.response();

        assertThat(children("/docs", "guide/readme.txt", null, response)).isNull();

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void the_route_and_the_console_screen_answer_the_same_children_for_one_folder() throws IOException {
        List<String> console = new RepositoryBrowse(store, () -> TENANT, ObservationRegistry.NOOP)
                .browseLevel(REPOSITORY, "/docs", "name", false).entries().stream()
                .map(RepositoryBrowse.BrowseEntry::name).toList();

        List<String> api = children("/docs", null, null, Servlets.response()).children().stream()
                .map(BrowseChildrenController.Child::name).toList();

        assertThat(api).isEqualTo(console).isNotEmpty();
    }
}
