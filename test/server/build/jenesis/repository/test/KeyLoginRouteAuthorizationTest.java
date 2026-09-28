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
 * The issued login keys are administered by the operator: listing, issuing and revoking over {@code /api/keylogin}
 * take a manage key of the operator tenant. A tenant's own administrator key is refused, since a login key can bind a
 * principal into any tenant - that is the case the classification exists for, and the one that passes without it -
 * and so is a key that may only publish, and a request with no key at all.
 */
class KeyLoginRouteAuthorizationTest {

    @TempDir
    Path root;

    @Test
    void only_a_manage_key_of_the_operator_tenant_administers_login_keys() throws IOException {
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
        String publisher = key(authorization, "operator", Authorization.REPOSITORY_READ,
                Authorization.REPOSITORY_WRITE);

        for (String[] route : List.of(new String[]{"GET", "/api/keylogin"}, new String[]{"POST", "/api/keylogin"},
                new String[]{"POST", "/api/keylogin/abc/delete"})) {
            String method = route[0], path = route[1];
            assertThat(granted(manager, method, path, operator))
                    .as("%s %s with a manage key of the operator tenant", method, path).isTrue();
            assertThat(granted(manager, method, path, tenantAdministrator))
                    .as("%s %s with a manage key of another tenant", method, path).isFalse();
            assertThat(granted(manager, method, path, publisher))
                    .as("%s %s with a key that may only publish", method, path).isFalse();
            assertThat(granted(manager, method, path, null))
                    .as("%s %s with no key", method, path).isFalse();
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
