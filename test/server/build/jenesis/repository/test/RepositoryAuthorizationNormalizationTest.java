package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryAuthorizationManager.Target;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1: the authorization manager classifies the surface from the <em>normalized</em> path, and the predicate it
 * reads is its own ({@link RepositoryAuthorizationManager#normalized}). This pins the
 * predicate transport-independently - Spring routes on the normalized path, so a URI carrying an empty ({@code //})
 * or dot ({@code /.} , {@code /..}) segment could reach a controller while the prefix-based scope and operator-tenant
 * classification misread it (e.g. {@code /api//settings} fails a {@code startsWith("/api/settings")} and skips the
 * operator gate). Such a URI is rejected before the decision; a legitimate artifact, {@code /api} or {@code /actuator}
 * path, including a directory-style trailing slash, is not.
 */
public class RepositoryAuthorizationNormalizationTest {

    @Test
    public void an_empty_or_dot_segment_is_not_normalized() {
        assertThat(RepositoryAuthorizationManager.normalized("/api//settings")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("/api//settings/jenreg.gate.enabled")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("//api/settings")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("/api/./settings")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("/api/../settings")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("/repository/releases/../secret/x")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("/api/settings/.")).isFalse();
        assertThat(RepositoryAuthorizationManager.normalized("/api/settings/..")).isFalse();
    }

    @Test
    public void a_clean_path_is_normalized() {
        assertThat(RepositoryAuthorizationManager.normalized("/api/settings")).isTrue();
        assertThat(RepositoryAuthorizationManager.normalized("/api/settings/jenreg.gate.enabled")).isTrue();
        assertThat(RepositoryAuthorizationManager.normalized("/api/repositories")).isTrue();
        assertThat(RepositoryAuthorizationManager.normalized("/actuator/prometheus")).isTrue();
        assertThat(RepositoryAuthorizationManager.normalized("/repository/releases/org/example/a/1/a-1.jar")).isTrue();
        // A directory-style trailing slash (a terminal empty segment) does not shift a prefix match and is left alone.
        assertThat(RepositoryAuthorizationManager.normalized("/repository/releases/")).isTrue();
    }

    @Test
    public void a_repository_path_scopes_to_its_second_segment_stripping_the_tenant() {
        // Every routing addresses /repository/<tenant>/<repo>/<sub>; the right is scoped to the repository (the SECOND
        // segment), the tenant prefix stripped, so a repository-scoped credential is not mis-checked against the tenant
        // name. The tenant is confined by the routing and governs the credential store, not the right's scope.
        Target target = RepositoryAuthorizationManager.classify("/repository/acme/releases/org/example/a/1/a-1.jar");
        assertThat(target.scope()).as("the repository is the second segment, not the tenant 'acme'").isEqualTo("releases");
        assertThat(target.subPath()).isEqualTo("org/example/a/1/a-1.jar");
        assertThat(target.manage()).isFalse();
        // A bare /repository/<tenant> names no repository, so only a deployment-wide right reaches it - and it is not
        // the registry probe, which only the bare /v2 is.
        Target bare = RepositoryAuthorizationManager.classify("/repository/acme");
        assertThat(bare.scope()).isEqualTo("*");
        assertThat(bare.subPath()).isNull();
        assertThat(bare.probe()).isFalse();
        assertThat(RepositoryAuthorizationManager.classify("/v2/acme/").probe()).isFalse();
    }

    @Test
    public void an_api_or_actuator_surface_is_manage_scoped() {
        // The management surfaces are never tenant-prefixed, so the tenant segment does not touch them - they stay manage.
        assertThat(RepositoryAuthorizationManager.classify("/api/settings").manage()).isTrue();
        assertThat(RepositoryAuthorizationManager.classify("/actuator/prometheus").manage()).isTrue();
    }
}
