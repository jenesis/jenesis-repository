package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.inventory.LicenseReport;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.service.RepositorySearch;
import build.jenesis.repository.search.web.BrowseController;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.web.testkit.Web;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.servlet.testkit.Servlets;
import jakarta.servlet.http.HttpServletRequest;
import build.jenesis.repository.server.kernel.Repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The browse and search endpoints, and the bound they answer under.
 *
 * <p>Both are request-time reads over a store that may hold ten million versions, so both are capped - and the cap
 * is only honest if the answer says it was reached. A silently truncated listing reads as a complete one, which is
 * the failure the bounded-read rule names: an operator concludes a coordinate is absent when the page simply
 * stopped. So the view carries the flag, and this asserts it is there and false on a page that was not cut short,
 * rather than absent or always true.
 *
 * <p>The prefix reaches the store as a key, so it is a traversal surface.
 */
public class BrowseControllerTest {

    private static final String REPO = "releases";

    @TempDir
    Path root;

    private BrowseController controller;

    @BeforeEach
    void wire() throws IOException {
        Repositories repositories = Web.repositories(root);
        controller = new BrowseController(repositories, Web.routing(repositories, Scopes.DEFAULT_TENANT),
                new RepositorySearch());
    }

    @Test
    void a_listing_states_whether_it_was_cut_short() throws Exception {
        var view = controller.browse(REPO, "", request(), Servlets.response().servlet());

        // The flag exists and is false here, which is the part worth pinning: a field that is always true, or that
        // was never populated, is indistinguishable from a correct one on a repository small enough to fit.
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void a_listing_echoes_the_prefix_it_answered_under() throws Exception {
        var view = controller.browse(REPO, "", request(), Servlets.response().servlet());

        assertThat(view.prefix()).isNotNull();
    }

    @Test
    void a_traversal_in_the_prefix_is_rejected() {
        for (String prefix : List.of("../", "com/../../etc", "..")) {
            assertThatThrownBy(() -> controller.browse(REPO, prefix, request(),
                    Servlets.response().servlet()))
                    .as("prefix %s", prefix).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void an_invalid_repository_name_is_refused_before_any_store_read() throws Exception {
        Servlets.Response response = Servlets.response();

        var view = controller.browse("../etc", "", request(), response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(view).isNull();
    }

    @Test
    void search_answers_an_empty_query_rather_than_refusing_it() throws Exception {
        // The console issues this on first paint. Refusing would make the screen's initial state an error.
        var view = controller.search(REPO, "", null, null, request(), Servlets.response().servlet());

        assertThat(view).isNotNull();
    }

    @Test
    void search_refuses_an_invalid_repository_name() throws Exception {
        Servlets.Response response = Servlets.response();

        var view = controller.search("../etc", "widget", null, null, request(), response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(view).isNull();
    }

    @Test
    void search_finds_a_package_by_the_name_its_publisher_gave_it() throws Exception {
        // NuGet keys a package pushed as Demo under demo, so a lookup by name reads the folded form too, or a package
        // is unfindable by the name its publisher gave it.
        new StoreRepositoryInventory(Web.store(root).scope(Scopes.DEFAULT_TENANT).scope(REPO))
                .record("NuGet", "demo", "1.0.0", Instant.now());

        var view = controller.search(REPO, "Demo", null, null, request(), Servlets.response().servlet());

        assertThat(view.results()).contains("demo:1.0.0");
    }

    @Test
    void search_says_how_the_repository_answers_it() throws Exception {
        Repositories repositories = Web.repositories(root);
        BrowseController controller = new BrowseController(repositories,
                Web.routing(repositories, Scopes.DEFAULT_TENANT), new RepositorySearch());
        new StoreRepositoryInventory(repositories.store(Scopes.DEFAULT_TENANT, REPO))
                .record("NuGet", "widget", "1.0.0", Instant.now());

        var byName = controller.search(REPO, "wid", null, null, request(), Servlets.response().servlet());
        assertThat(byName.mode()).as("a repository with nothing set looks a package up by name").isEqualTo("NAME");
        assertThat(byName.indexed()).isFalse();
        assertThat(byName.results()).containsExactly("widget:1.0.0");
        assertThat(byName.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.ecosystem()).isEqualTo("NuGet");
            assertThat(hit.coordinate()).isEqualTo("widget");
            assertThat(hit.version()).isEqualTo("1.0.0");
        });

        repositories.live().settings().setRepository(Scopes.DEFAULT_TENANT, REPO,
                Map.of(SearchMode.SETTING, "true"));
        var fullText = controller.search(REPO, "wid", null, null, request(), Servlets.response().servlet());
        assertThat(fullText.mode()).as("switched on, the repository answers in full text").isEqualTo("FULL_TEXT");
        assertThat(fullText.indexed()).as("but its index is not built yet, so the name lookup answers meanwhile")
                .isFalse();
        assertThat(fullText.results()).containsExactly("widget:1.0.0");
    }

    @Test
    void search_pages_by_its_cursor_and_refuses_one_it_did_not_hand_out() throws Exception {
        Repositories repositories = Web.repositories(root);
        BrowseController controller = new BrowseController(repositories,
                Web.routing(repositories, Scopes.DEFAULT_TENANT), new RepositorySearch());
        StoreRepositoryInventory inventory =
                new StoreRepositoryInventory(repositories.store(Scopes.DEFAULT_TENANT, REPO));
        for (int index = 0; index < 5; index++) {
            inventory.record("NuGet", "widget" + index, "1.0.0", Instant.now());
        }

        var first = controller.search(REPO, "widget", null, 2, request(), Servlets.response().servlet());
        assertThat(first.results()).hasSize(2);
        assertThat(first.truncated()).isTrue();
        List<String> all = new ArrayList<>(first.results());
        var page = first;
        while (page.nextCursor() != null) {
            page = controller.search(REPO, "widget", page.nextCursor(), 2, request(), Servlets.response().servlet());
            all.addAll(page.results());
        }
        assertThat(all).doesNotHaveDuplicates().hasSize(5);

        assertThatThrownBy(() -> controller.search(REPO, "widget", "not-a-cursor", 2, request(),
                Servlets.response().servlet())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_licence_inventory_answers_its_state_and_a_count_is_started_only_when_asked() throws Exception {
        Repositories repositories = Web.repositories(root);
        BrowseController controller = new BrowseController(repositories,
                Web.routing(repositories, Scopes.DEFAULT_TENANT), new RepositorySearch());
        var store = repositories.store(Scopes.DEFAULT_TENANT, REPO);
        new StoreRepositoryInventory(store).record("NuGet", "widget", "1.0.0", Instant.now());

        Servlets.Response read = Servlets.response();
        var before = controller.licenses(REPO, false, request(), read.servlet());
        assertThat(before.state()).as("a plain read starts nothing").isEqualTo("not-counted");
        assertThat(read.header(BrowseController.REFRESH_HEADER)).isNull();

        Servlets.Response started = Servlets.response();
        var running = controller.licenses(REPO, true, request(), started.servlet());
        assertThat(started.header(BrowseController.REFRESH_HEADER)).isEqualTo("started");
        assertThat(running.state()).as("the answer is the state as it then stands, never the finished count")
                .isIn("running", "done");
        assertThat(StoredReport.awaitSettled(store, LicenseReport.NAME, Duration.ofMinutes(1))).isPresent();

        var done = controller.licenses(REPO, false, request(), Servlets.response().servlet());
        assertThat(done.state()).isEqualTo("done");
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.versions()).as("counted with the repository's full-text search off").isEqualTo(1);
        assertThat(done.categories()).extracting(BrowseController.LicenseCount::value).containsExactly("unknown");
    }

    @Test
    void the_licence_inventory_refuses_an_invalid_repository_name() throws Exception {
        Servlets.Response response = Servlets.response();

        assertThat(controller.licenses("../etc", true, request(), response.servlet())).isNull();
        assertThat(response.status()).isEqualTo(400);
    }

    private static HttpServletRequest request() {
        return Servlets.request("GET", "/api/search");
    }
}
