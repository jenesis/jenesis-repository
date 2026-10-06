package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.dependents.DeclaredDependents;
import build.jenesis.repository.dependents.web.Declarations;
import build.jenesis.repository.dependents.web.DependentsController;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
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
 * The reverse-dependency endpoint's refusals.
 *
 * <p>This endpoint answers "what breaks if this coordinate turns out to be vulnerable", so the interesting cases
 * are the ones where it cannot answer. It distinguishes <em>not installed</em> (501 - this deployment does not
 * carry the module) from <em>not built yet</em> (503 - it does, and the index sweep has not run), which is the same
 * distinction the CLI's exit code 3 exists for: both are a missing answer and only one of them is worth waiting
 * for. Collapsing them, or answering either as an empty list, would report "nothing depends on this" for a
 * coordinate half the estate depends on - a wrong answer that reads exactly like a right one.
 *
 * <p>The coordinate's traversal guard is deliberately not asserted here: it sits behind the built-index check, so
 * reaching it needs an index this suite would have to build, and a test that drove the sweep to get there would be
 * testing the sweep. What is covered is the mapping the guard relies on - the handler's own exception handler
 * answering a bad request rather than letting the raise become a 500.
 */
public class DependentsControllerTest {

    @TempDir
    Path root;

    private DependentsController controller() throws IOException {
        Repositories repositories = Web.repositories(root);
        return new DependentsController(repositories, Web.routing(repositories, Scopes.DEFAULT_TENANT));
    }

    @Test
    void an_index_that_has_not_been_built_says_so_rather_than_reporting_no_dependents() throws Exception {
        Servlets.Response response = Servlets.response();

        Object view = controller().dependents("releases", null, null, null, "", 500,
                Servlets.request("GET", "/ui/dependents"), response.servlet());

        // 503, not an empty page: "not computed yet" and "nothing depends on it" are opposite answers to the
        // question an operator is asking when they open this.
        assertThat(response.status()).isEqualTo(503);
        assertThat(view).isNull();
    }

    @Test
    void the_declared_tier_says_it_is_not_built_until_its_first_full_pass() throws Exception {
        Servlets.Response response = Servlets.response();

        Object view = controller().dependents("releases", null, "lodash", null, "", 500,
                Servlets.request("GET", "/ui/dependents"), response.servlet());

        assertThat(response.status()).isEqualTo(503);
        assertThat(view).isNull();
    }

    @Test
    void the_declared_tier_names_only_versions_still_published_and_apart_from_the_dependents() throws Exception {
        Repositories repositories = Web.repositories(root);
        ArtifactStore store = repositories.store(Scopes.DEFAULT_TENANT, "releases");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.recording("npm", "app", "1.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "^4.17.0"))).commit();
        inventory.recording("npm", "lib", "2.0.0", false, Instant.now())
                .file("/package.tgz")
                .dependencies(List.of(new DependencySection.Declared("lodash", "4.17.21"))).commit();
        new DeclaredDependents(store).pass(_ -> null);
        // Deleted since the pass: the tier still records it until its next full pass, and the answer must not name it.
        store.delete(MetadataKey.version("npm", "app", "1.0.0"));
        Servlets.Response response = Servlets.response();

        DependentsController.DependentsView view = new DependentsController(repositories, Web.routing(repositories,
                Scopes.DEFAULT_TENANT))
                .dependents("releases", null, "lodash", null, "", 500, Servlets.request("GET", "/ui/dependents"),
                        response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(view.declared()).containsExactly(new Declarations.Row("npm", "lib", "2.0.0", "4.17.21", null));
        assertThat(view.dependency()).isEqualTo("lodash");
        assertThat(view.declaredLastBuilt()).isNotNull();
        assertThat(view.dependents()).as("a requirement is not a resolved dependent").isNull();
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
        new DeclaredDependents(store).pass(_ -> null);

        DependentsController.DependentsView view = new DependentsController(repositories, Web.routing(repositories,
                Scopes.DEFAULT_TENANT))
                .dependents("releases", null, "lodash", "4.17.21", "", 500, Servlets.request("GET", "/ui/dependents"),
                        Servlets.response().servlet());

        assertThat(view.declared()).extracting(Declarations.Row::coordinate, Declarations.Row::admits)
                .containsExactly(tuple("app", "admits"), tuple("lib", "excludes"),
                        // A dist-tag is not a range: what it names is decided by the registry, not the requirement.
                        tuple("tool", "unknown"));
    }

    @Test
    void an_invalid_repository_name_is_refused_before_any_store_read() throws Exception {
        Servlets.Response response = Servlets.response();

        Object view = controller().dependents("../etc", null, null, null, "", 500,
                Servlets.request("GET", "/ui/dependents"), response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(view).isNull();
    }

    @Test
    void the_exception_handler_answers_a_bad_request() throws Exception {
        Servlets.Response response = Servlets.response();

        controller().badRequest(response.servlet());

        assertThat(response.status()).isEqualTo(400);
    }
}
