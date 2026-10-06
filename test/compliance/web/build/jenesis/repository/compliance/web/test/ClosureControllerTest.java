package build.jenesis.repository.compliance.web.test;

import module java.base;
import module org.junit.jupiter.api;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ScreenedThrough;
import build.jenesis.repository.compliance.web.ClosureController;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.web.testkit.Web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The closure endpoint's answers and refusals: a version's closure read back as the closure pass recorded it, a
 * release the pass has not reached as {@code PENDING} rather than as an empty closure, and a cached copy as
 * {@code CACHED}, screened through the enabled feeds covering its ecosystem - each saying what screened it, so a
 * version nothing screened never reads as clean.
 */
class ClosureControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @TempDir
    Path root;

    private Repositories repositories;
    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        repositories = Web.repositories(root);
        store = repositories.store(Scopes.DEFAULT_TENANT, "releases");
    }

    @Test
    void a_request_naming_no_coordinate_or_an_invalid_repository_is_refused() {
        for (List<String> asked : List.of(List.of("releases", "npm", "", "1.0.0"),
                List.of("releases", "", "app", "1.0.0"), List.of("../releases", "npm", "app", "1.0.0"))) {
            Servlets.Response response = Servlets.response();

            ClosureController.ClosureView view = controller(Map.of()).closure(asked.get(0), asked.get(1),
                    asked.get(2), asked.get(3), request(), response.servlet());

            assertThat(response.status()).as("%s", asked).isEqualTo(400);
            assertThat(view).isNull();
        }
    }

    @Test
    void a_version_the_repository_does_not_hold_is_not_found() {
        Servlets.Response response = Servlets.response();

        assertThat(controller(Map.of()).closure("releases", "npm", "app", "1.0.0", request(), response.servlet()))
                .isNull();
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void a_release_the_pass_has_not_reached_is_pending_and_screened_through_nothing() throws IOException {
        publish("app", "1.0.0");
        Servlets.Response response = Servlets.response();

        ClosureController.ClosureView view = controller(Map.of()).closure("releases", "npm", "app", "1.0.0",
                request(), response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(view.state()).isEqualTo("PENDING");
        assertThat(view.resolved()).isNull();
        assertThat(view.components()).isEmpty();
        assertThat(view.screenedThrough()).as("a release with no closure: nothing has screened it")
                .isEqualTo(ScreenedThrough.published(false));
    }

    @Test
    void a_resolved_closure_answers_as_the_pass_recorded_it() throws IOException {
        publish("app", "1.0.0");
        ClosureSection.Closure closure = new ClosureSection.Closure(ClosureSection.Status.PARTIAL,
                List.of(new ClosureSection.Component("lodash", "4.17.21", true, 1, "")),
                List.of(new ClosureSection.Cut("minimist", "1.2.5", "every held version it admits is held for review")),
                false, NOW, ClosureSource.Kind.DECLARATIONS, "declarations");
        MetadataProvider.installed().over(store).mutate("npm", "app", "1.0.0", ClosureSection.TAG,
                ClosureSection.record(closure));

        ClosureController.ClosureView view = controller(Map.of()).closure("releases", "npm", "app", "1.0.0",
                request(), Servlets.response().servlet());

        assertThat(view.state()).isEqualTo("PARTIAL");
        assertThat(view.resolved()).isEqualTo(NOW.toString());
        assertThat(view.kind()).isEqualTo("DECLARATIONS");
        assertThat(view.source()).isEqualTo("declarations");
        assertThat(view.components()).extracting(ClosureSection.Component::coordinate,
                ClosureSection.Component::version, ClosureSection.Component::cached)
                .containsExactly(tuple("lodash", "4.17.21", true));
        assertThat(view.cuts()).extracting(ClosureSection.Cut::coordinate, ClosureSection.Cut::reason)
                .containsExactly(tuple("minimist", "every held version it admits is held for review"));
        assertThat(view.exposure()).as("nothing derived yet").isNull();
        assertThat(view.screenedThrough()).isEqualTo(ScreenedThrough.published(true));
    }

    @Test
    void a_cached_copy_is_screened_through_the_enabled_feeds_covering_its_ecosystem() throws IOException {
        new StoreRepositoryInventory(store).cache("npm", "lodash", "4.17.21", "https://registry.example/", NOW);
        SequencedMap<String, AdvisorySource> feeds = new LinkedHashMap<>();
        feeds.put("osv", AdvisorySource.of(Map.of()));

        ClosureController.ClosureView covered = controller(feeds).closure("releases", "npm", "lodash", "4.17.21",
                request(), Servlets.response().servlet());
        ClosureController.ClosureView uncovered = controller(Map.of()).closure("releases", "npm", "lodash",
                "4.17.21", request(), Servlets.response().servlet());

        assertThat(covered.state()).isEqualTo("CACHED");
        assertThat(covered.components()).as("a copy has no closure of its own").isEmpty();
        assertThat(covered.screenedThrough()).isEqualTo(new ScreenedThrough(ScreenedThrough.Basis.FEEDS,
                List.of("osv")));
        assertThat(uncovered.screenedThrough()).as("no feed on: unscreened, not clean")
                .isEqualTo(new ScreenedThrough(ScreenedThrough.Basis.UNCOVERED, List.of()));
    }

    private ClosureController controller(Map<String, AdvisorySource> feeds) {
        SequencedMap<String, AdvisorySource> enabled = new LinkedHashMap<>(feeds);
        return new ClosureController(repositories, Web.routing(repositories, Scopes.DEFAULT_TENANT), () -> enabled);
    }

    private void publish(String coordinate, String version) throws IOException {
        new StoreRepositoryInventory(store).recording("npm", coordinate, version, false, NOW).file("/package.tgz")
                .commit();
    }

    private static jakarta.servlet.http.HttpServletRequest request() {
        return Servlets.request("GET", "/api/repository/closure");
    }
}
