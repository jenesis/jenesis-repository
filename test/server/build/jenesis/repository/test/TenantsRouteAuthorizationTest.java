package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Creating or deleting a tenant over {@code /api/admin/tenants} is administration over every repository: a key that
 * may publish into every repository - the shape of a CI key - is refused, and a key holding the manage rights over
 * all of them is let through to the controller, which then holds it to the operator tenant.
 */
class TenantsRouteAuthorizationTest {

    @TempDir
    Path root;

    @Test
    void a_wildcard_publish_right_does_not_administer_tenants_and_the_manage_right_does() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Authorization authorization = Authorization.enforcing(store);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setOperatorTenant("operator");
        RepositoryAuthorizationManager manager = new RepositoryAuthorizationManager(authorization,
                KeyUsageTracker.NONE, properties);

        String publisher = Authorization.mint("operator");
        authorization.provision("operator", Authorization.hash(publisher), "ci", null);
        authorization.grant(publisher, "*", Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE);
        String administrator = Authorization.mint("operator");
        authorization.provision("operator", Authorization.hash(administrator), "admin", null);
        authorization.grant(administrator, "*", Authorization.MANAGE_READ, Authorization.MANAGE_WRITE);

        for (String method : List.of("GET", "PUT", "DELETE")) {
            assertThat(granted(manager, method, publisher))
                    .as("%s with a key that may only publish", method).isFalse();
            assertThat(granted(manager, method, administrator))
                    .as("%s with a key that may administer", method).isTrue();
        }
    }

    private static boolean granted(RepositoryAuthorizationManager manager, String method, String key) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI())
                .thenReturn(method.equals("GET") ? "/api/admin/tenants" : "/api/admin/tenants/acme");
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doNothing().when(request).setAttribute(anyString(), any());
        return manager.authorize(() -> null, new RequestAuthorizationContext(request)).isGranted();
    }
}
