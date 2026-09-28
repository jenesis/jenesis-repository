package build.jenesis.repository.ui.admin.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.MembershipCache;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.identity.UserDirectory.Role;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.ui.admin.web.TenantsController;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Switching the console's active tenant is gated: {@code /tenants/select} is only {@code .authenticated()} in the
 * security config, so this membership re-check is the sole barrier stopping a signed-in user from switching into a
 * tenant it does not belong to (and then reading that tenant's credentials and projects). A member may select its
 * tenant, a super-admin may select any, and a non-member or an unknown tenant is refused - alike, so a member cannot
 * learn which tenants exist by selecting names, unless the deployment's {@link AccessDenial} says to refuse honestly.
 */
public class TenantsControllerTest {

    @TempDir
    private Path root;

    private TenantsController controller;
    private String selected;

    @BeforeEach
    public void setUp() throws IOException {
        Documents rootStorage = CacheStorages.documents(root);
        TenantService tenants = new TenantService(rootStorage);
        tenants.create("acme");
        tenants.create("globex");
        new UserDirectory(Authorization.enforcing(rootStorage.store()), "acme").put("github/1", Role.VIEWER, "octo");
        ArtifactStore repositoryStore = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.resolve("repo").toString() : null);
        TenantPurge purge = new TenantPurge(tenants, repositoryStore, Authorization.enforcing(repositoryStore),
                AuditTrail.NONE, () -> "octo", "operator");
        SessionCurrentTenant current = new SessionCurrentTenant() {
            @Override
            public void select(String tenant) {
                selected = tenant;
            }
        };
        controller = new TenantsController(tenants, purge,
                new Memberships(Authorization.enforcing(rootStorage.store()), tenants,
                        new MembershipCache()),
                current);
    }

    @Test
    public void a_member_may_select_its_own_tenant() {
        assertThat(controller.select("acme", member("github/1"))).isEqualTo("redirect:/ui/repositories");
        assertThat(selected).isEqualTo("acme");
    }

    @AfterEach
    public void restore() {
        Features.reset();
    }

    @Test
    public void a_non_member_is_told_what_an_absent_tenant_is_told() {
        Features.configure(_ -> null);
        assertThatThrownBy(() -> controller.select("globex", member("github/1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No such tenant 'globex'.");
        assertThatThrownBy(() -> controller.select("nope", member("github/1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No such tenant 'nope'.");
        assertThat(selected).as("the tenant was not switched").isNull();
    }

    @Test
    public void a_non_member_is_refused_honestly_when_the_deployment_says_so() {
        Features.configure(Map.of("jenrepo." + AccessDenial.KEY, AccessDenial.FORBIDDEN_VALUE)::get);
        assertThatThrownBy(() -> controller.select("globex", member("github/1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("do not have access");
        assertThatThrownBy(() -> controller.select("nope", member("github/1")))
                .as("and so is a tenant that does not exist, since the refusal comes before it is looked up")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("do not have access");
        assertThat(selected).as("the tenant was not switched").isNull();
    }

    @Test
    public void a_super_admin_may_select_any_tenant() {
        assertThat(controller.select("globex", superadmin("github/9"))).isEqualTo("redirect:/ui/repositories");
        assertThat(selected).isEqualTo("globex");
    }

    @Test
    public void a_super_admin_is_told_a_tenant_does_not_exist() {
        assertThatThrownBy(() -> controller.select("nope", superadmin("github/9")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No such tenant");
    }

    private static Authentication member(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_USER");
    }

    private static Authentication superadmin(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_SUPERADMIN");
    }
}
