package build.jenesis.repository.ui.admin.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.MembershipCache;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.identity.UserDirectory.Role;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.ui.admin.web.InstancesController;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Switching the console's active tenant is gated: {@code /instances/select} is only {@code .authenticated()} in the
 * security config, so this membership re-check is the sole barrier stopping a signed-in user from switching into a
 * tenant it does not belong to (and then reading that tenant's credentials and projects). A member may select its
 * tenant, a super-admin may select any, and a non-member or an unknown tenant is refused.
 */
public class InstancesControllerTest {

    @TempDir
    private Path root;

    private InstancesController controller;
    private String selected;

    @BeforeEach
    public void setUp() throws IOException {
        Documents rootStorage = CacheStorages.documents(root);
        TenantService tenants = new TenantService(rootStorage);
        tenants.create("acme");
        tenants.create("globex");
        new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").put("github/1", Role.VIEWER, "octo");
        ArtifactStore repositoryStore = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.resolve("repo").toString() : null);
        TenantPurge purge = new TenantPurge(tenants, repositoryStore, Authorization.enforcing(repositoryStore),
                AuditTrail.NONE, () -> "octo", "operator");
        SessionCurrentTenant current = new SessionCurrentTenant() {
            @Override
            public void select(String tenant) {
                selected = tenant;
            }
        };
        controller = new InstancesController(tenants, purge,
                new Memberships(rootStorage, Authorization.enforcing(rootStorage.store()), tenants,
                        new MembershipCache()),
                current);
    }

    @Test
    public void a_member_may_select_its_own_tenant() {
        assertThat(controller.select("acme", member("github/1"))).isEqualTo("redirect:/ui/repositories");
        assertThat(selected).isEqualTo("acme");
    }

    @Test
    public void a_non_member_is_refused() {
        assertThatThrownBy(() -> controller.select("globex", member("github/1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("do not have access");
        assertThat(selected).as("the tenant was not switched").isNull();
    }

    @Test
    public void a_super_admin_may_select_any_tenant() {
        assertThat(controller.select("globex", superadmin("github/9"))).isEqualTo("redirect:/ui/repositories");
        assertThat(selected).isEqualTo("globex");
    }

    @Test
    public void an_unknown_tenant_is_refused() {
        assertThatThrownBy(() -> controller.select("nope", member("github/1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No such tenant");
    }

    private static Authentication member(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_USER");
    }

    private static Authentication superadmin(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_SUPERADMIN");
    }
}
