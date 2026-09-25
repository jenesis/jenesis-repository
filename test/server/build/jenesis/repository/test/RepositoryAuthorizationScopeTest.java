package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryAuthorizationManager.Target;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization scope must match the repository the routing actually serves. A request under
 * {@code /repository/<tenant>/<repo>/...} or the OCI registry's {@code /v2/<tenant>/<repo>/...} is authorized against
 * {@code <repo>}; a deployment-management surface ({@code /api}, {@code /actuator}) against the whole deployment
 * ({@code *}); and the registry's version probe, which names no repository, only asks whether the credential is accepted. Authorizing a
 * registry path against the literal {@code v2} would check one repository and serve another. This pins the
 * classification transport-independently.
 */
public class RepositoryAuthorizationScopeTest {

    @Test
    public void a_repository_path_scopes_to_the_repository_after_its_tenant() {
        Target target = RepositoryAuthorizationManager.classify("/repository/default/releases/org/example/a/1/a-1.jar");
        assertThat(target.scope()).isEqualTo("releases");
        assertThat(target.subPath()).isEqualTo("org/example/a/1/a-1.jar");
        assertThat(target.manage()).isFalse();
        assertThat(target.probe()).isFalse();
    }

    @Test
    public void a_management_surface_scopes_to_the_whole_deployment() {
        assertThat(RepositoryAuthorizationManager.classify("/api/settings/x"))
                .isEqualTo(new Target("*", null, true));
        assertThat(RepositoryAuthorizationManager.classify("/actuator/prometheus"))
                .isEqualTo(new Target("*", null, true));
        assertThat(RepositoryAuthorizationManager.classify("/actuator"))
                .isEqualTo(new Target("*", null, true));
    }

    @Test
    public void a_registry_path_scopes_to_the_repository_after_its_tenant() {
        // The routing serves /v2/<tenant>/<repository>/<image>/... out of <repository>, so the right is checked against
        // that repository - never against the literal "v2" segment or the tenant - with the image's name within it as
        // the sub-path.
        Target manifest = RepositoryAuthorizationManager.classify("/v2/default/images/myimage/manifests/latest");
        assertThat(manifest.scope()).isEqualTo("images");
        assertThat(manifest.subPath()).isEqualTo("myimage/manifests/latest");
        assertThat(manifest.manage()).isFalse();
        assertThat(manifest.probe()).isFalse();
    }

    @Test
    public void the_registry_probe_asks_only_whether_the_credential_is_accepted() {
        // GET /v2/ names no repository: a client asks it before it names any image, so a credential scoped to one
        // repository must pass it.
        for (String probe : List.of("/v2/", "/v2")) {
            Target target = RepositoryAuthorizationManager.classify(probe);
            assertThat(target.probe()).as(probe).isTrue();
            assertThat(target.manage()).as(probe).isFalse();
        }
    }

    @Test
    public void an_operation_on_one_repository_scopes_to_the_repository_its_query_names() {
        // Cleanup, retention, pins, an import and a staged release's promotion answer beside the repository, under
        // /api/repository/, and take that repository's rights - not a deployment-wide manage right - so a key scoped to
        // one repository may operate on it and on no other.
        assertThat(RepositoryAuthorizationManager.classify("/api/repository/cleanup", "repo=releases"))
                .isEqualTo(new Target("releases", null, false));
        assertThat(RepositoryAuthorizationManager.classify("/api/repository/staging/rc1/promote", "x=1&repo=releases"))
                .isEqualTo(new Target("releases", null, false));
    }

    @Test
    public void an_operation_naming_no_single_repository_is_a_deployment_surface() {
        // With no repo, or with two, the handler would act on something other than what was authorized, so the
        // request falls back to the management scope a repository key does not hold.
        assertThat(RepositoryAuthorizationManager.classify("/api/repository/cleanup", null))
                .isEqualTo(new Target("*", null, true));
        assertThat(RepositoryAuthorizationManager.classify("/api/repository/cleanup", "repo=releases&repo=other"))
                .isEqualTo(new Target("*", null, true));
        // The repository definitions are a deployment-wide surface whatever their query says.
        assertThat(RepositoryAuthorizationManager.classify("/api/repositories/releases", "repo=releases"))
                .isEqualTo(new Target("*", null, true));
    }

    @Test
    public void a_staged_upload_scopes_to_the_repository_after_its_tenant() {
        Target target = RepositoryAuthorizationManager.classify("/staging/default/releases/rc1/maven/org/a/1/a-1.pom");
        assertThat(target.scope()).isEqualTo("releases");
        assertThat(target.subPath()).isEqualTo("rc1/maven/org/a/1/a-1.pom");
        assertThat(target.manage()).isFalse();
    }
}
