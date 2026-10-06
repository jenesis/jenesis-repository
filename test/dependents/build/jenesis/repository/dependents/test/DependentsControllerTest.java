package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureTask;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.dependents.web.Declarations;
import build.jenesis.repository.dependents.web.Dependents;
import build.jenesis.repository.dependents.web.DependentsController;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.scope.Scopes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The dependents endpoint's answers and refusals.
 *
 * <p>The declared rows are the closure pass's, so a test seeds them by running it. Its declared half says when they
 * have not been built yet rather than answering an empty list, which would
 * report "nothing declares this" for a package half the estate names - a wrong answer that reads exactly like a right
 * one - and it says so inside the answer, so the resolved half still stands beside it.
 */
public class DependentsControllerTest {

    @TempDir
    Path root;

    private DependentsController controller() throws IOException {
        Repositories repositories = Web.repositories(root);
        return new DependentsController(repositories, Web.routing(repositories, Scopes.DEFAULT_TENANT));
    }

    @Test
    void a_request_naming_no_ecosystem_is_refused() throws Exception {
        Servlets.Response response = Servlets.response();

        Object view = controller().dependents("releases", "", "lodash", null, "", "", 50,
                Servlets.request("GET", "/api/repository/dependents"), response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(view).isNull();
    }

    @Test
    void the_declared_half_says_it_is_not_built_until_its_first_full_pass_and_no_version_asks_no_resolved_half()
            throws Exception {
        Servlets.Response response = Servlets.response();

        Dependents.View view = controller().dependents("releases", "npm", "lodash", null, "", "", 50,
                Servlets.request("GET", "/api/repository/dependents"), response.servlet());

        assertThat(response.status()).as("a half that cannot answer says so in the answer").isEqualTo(200);
        assertThat(view.declared()).isEqualTo(new Dependents.Declared(true, null, List.of(), null));
        assertThat(view.resolved()).as("no version, so no published version built against it to list").isNull();
    }

    @Test
    void the_index_names_only_versions_still_published() throws Exception {
        Repositories repositories = Web.repositories(root);
        ArtifactStore store = repositories.store(Scopes.DEFAULT_TENANT, "releases");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.recording("npm", "app", "1.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "^4.17.0"))).commit();
        inventory.recording("npm", "lib", "2.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "4.17.21"))).commit();
        closurePass(store);
        // Deleted since the pass: its row stays until the reconcile's next full pass, and the answer must not name it.
        store.delete(MetadataKey.version("npm", "app", "1.0.0"));
        Servlets.Response response = Servlets.response();

        Dependents.View view = new DependentsController(repositories, Web.routing(repositories,
                Scopes.DEFAULT_TENANT))
                .dependents("releases", "npm", "lodash", null, "", "", 50,
                        Servlets.request("GET", "/api/repository/dependents"), response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(view.declared().declarations())
                .containsExactly(new Declarations.Row("npm", "lib", "2.0.0", "4.17.21", null));
        assertThat(view.coordinate()).isEqualTo("lodash");
        assertThat(view.declared().built()).isNotNull();
    }

    @Test
    void asked_about_a_version_each_declared_row_says_whether_its_requirement_admits_it() throws Exception {
        Repositories repositories = Web.repositories(root);
        ArtifactStore store = repositories.store(Scopes.DEFAULT_TENANT, "releases");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.recording("npm", "app", "1.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "^4.17.0"))).commit();
        inventory.recording("npm", "lib", "2.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "~4.16.0"))).commit();
        inventory.recording("npm", "tool", "3.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "latest"))).commit();
        inventory.recording("npm", "any", "4.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", ""))).commit();
        closurePass(store);

        Dependents.View view = new DependentsController(repositories, Web.routing(repositories,
                Scopes.DEFAULT_TENANT))
                .dependents("releases", "npm", "lodash", "4.17.21", "", "", 50,
                        Servlets.request("GET", "/api/repository/dependents"), Servlets.response().servlet());

        assertThat(view.declared().declarations()).extracting(Declarations.Row::coordinate, Declarations.Row::admits)
                .containsExactlyInAnyOrder(tuple("app", "admits"), tuple("lib", "excludes"),
                        // A dist-tag is not a range: what it names is decided by the registry, not the requirement.
                        tuple("tool", "unknown"),
                        tuple("any", "admits"));
        assertThat(view.declared().declarations()).filteredOn(row -> row.coordinate().equals("any"))
                .as("a requirement stating no version admits every one").extracting(Declarations.Row::admits)
                .containsExactly("admits");
    }

    @Test
    void a_declaration_stating_no_requirement_admits_every_version_in_every_ecosystem() throws Exception {
        Repositories repositories = Web.repositories(root);
        ArtifactStore store = repositories.store(Scopes.DEFAULT_TENANT, "releases");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Map<String, String> dependencies = Map.of("Maven", "org.dep:lib", "PyPI", "lib", "crates.io", "lib",
                "RubyGems", "lib");
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            inventory.recording(entry.getKey(), "app-" + entry.getKey().toLowerCase(Locale.ROOT), "1.0", false,
                            Instant.now())
                    .file("/app-" + entry.getKey())
                    .dependencies(List.of(new DependencySection.Declared(entry.getValue(), ""))).commit();
        }
        closurePass(store);

        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            Dependents.View view = controller().dependents("releases", entry.getKey(), entry.getValue(), "1.0", "",
                    "", 50, Servlets.request("GET", "/api/repository/dependents"), Servlets.response().servlet());
            assertThat(view.declared().declarations()).as(entry.getKey())
                    .extracting(Declarations.Row::admits).containsExactly("admits");
        }
    }

    @Test
    void an_invalid_repository_name_is_refused_before_any_store_read() throws Exception {
        Servlets.Response response = Servlets.response();

        Object view = controller().dependents("../etc", "npm", "lodash", null, "", "", 50,
                Servlets.request("GET", "/api/repository/dependents"), response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(view).isNull();
    }

    @Test
    void the_exception_handler_answers_a_bad_request() throws Exception {
        Servlets.Response response = Servlets.response();

        controller().badRequest(response.servlet());

        assertThat(response.status()).isEqualTo(400);
    }

    /** The closure pass over {@code store}, the {@code releases} repository, which keeps its declared rows. */
    private static void closurePass(ArtifactStore store) throws IOException {
        UnitFailures failures = new UnitFailures("the closure pass", "nothing");
        new ClosureTask(Duration.ofMinutes(5), ClosureSource.installed()).repository(new Pass(store, failures));
        failures.rethrow();
    }

    /** One repository's pass with nothing configured: every setting at its default. */
    private record Pass(ArtifactStore store, UnitFailures failures) implements RepositoryContext {

        @Override
        public String tenant() {
            return Scopes.DEFAULT_TENANT;
        }

        @Override
        public String repository() {
            return "releases";
        }

        @Override
        public UnaryOperator<String> config() {
            return _ -> null;
        }

        @Override
        public UnitFailures failures(String work, String consequence) {
            return failures;
        }

        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public void gauge(String name, String description, Map<String, String> tags, double value) {
        }

        @Override
        public TenantView tenantView() {
            return TenantView.NONE;
        }
    }
}
