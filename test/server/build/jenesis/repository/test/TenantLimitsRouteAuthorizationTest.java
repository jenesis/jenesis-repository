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
 * A tenant's quota and rate limit are the operator's ceilings on it: setting either takes a manage key of the operator
 * tenant, so a tenant's own administrator cannot lift its own ceiling - the case that passes without the
 * classification - while reading them stays the tenant's own.
 */
class TenantLimitsRouteAuthorizationTest {

    @TempDir
    Path root;

    @Test
    void only_the_operator_sets_a_tenants_limits_and_the_tenant_still_reads_them() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        Authorization authorization = Authorization.enforcing(store);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setOperatorTenant("operator");
        RepositoryAuthorizationManager manager = new RepositoryAuthorizationManager(authorization,
                KeyUsageTracker.NONE, properties);

        String operator = key(authorization, "operator", Authorization.MANAGE_READ, Authorization.MANAGE_WRITE);
        String tenantAdministrator = key(authorization, "acme", Authorization.MANAGE_READ,
                Authorization.MANAGE_WRITE);

        for (String path : List.of("/api/quota", "/api/rate-limit")) {
            assertThat(granted(manager, "PUT", path, operator)).as("the operator sets %s", path).isTrue();
            assertThat(granted(manager, "PUT", path, tenantAdministrator))
                    .as("a tenant's own administrator does not set %s", path).isFalse();
            assertThat(granted(manager, "GET", path, tenantAdministrator))
                    .as("but reads its own %s", path).isTrue();
        }
    }

    private static String key(Authorization authorization, String tenant, String... rights) throws IOException {
        String key = Authorization.mint(tenant);
        authorization.provision(tenant, Authorization.hash(key), "test", null);
        authorization.grant(key, "*", rights);
        return key;
    }

    private static boolean granted(RepositoryAuthorizationManager manager, String method, String path, String key) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doNothing().when(request).setAttribute(anyString(), any());
        return manager.authorize(() -> null, new RequestAuthorizationContext(request)).isGranted();
    }
}
