package build.jenesis.repository.ui.admin.test;

import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.identity.UserDirectory.Role;
import build.jenesis.repository.ui.admin.web.AdminController;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-tenant admin screen must not let an admin demote themselves out of the last admin role: the screen requires
 * admin, so a tenant with no admin can no longer manage its own membership. Self-demotion is allowed only while another
 * member still holds admin.
 */
public class AdminSelfDemotionTest {

    @TempDir
    private Path root;

    private UserDirectory directory;
    private AdminController controller;

    @BeforeEach
    public void setUp() {
        Documents documents = CacheStorages.documents(root);
        directory = new UserDirectory(Authorization.enforcing(documents.store()), "acme");
        // Authorization.anonymous() because this suite is about the user directory: the controller holds one only
        // for the fleet-wide half of its cache clear, which nothing here calls.
        controller = new AdminController(directory, new KnownPrincipals(Authorization.anonymous()),
                documents.scope("acme"), AuditTrail.none(), () -> "acme",
                () -> "root",
                Authorization.anonymous());
    }

    @Test
    public void the_last_admin_cannot_demote_themselves() throws IOException {
        directory.put("admin-a", Role.ADMIN, "a");
        directory.put("viewer-x", Role.VIEWER, "x");

        assertThatThrownBy(() -> controller.addUser("admin-a", "viewer", null, auth("admin-a"), redirect()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last admin");
        assertThat(directory.find("admin-a").orElseThrow().role()).as("still admin - the demotion was refused")
                .isEqualTo(Role.ADMIN);
    }

    @Test
    public void an_admin_may_demote_themselves_while_another_admin_remains() throws IOException {
        directory.put("admin-a", Role.ADMIN, "a");
        directory.put("admin-b", Role.ADMIN, "b");

        controller.addUser("admin-a", "viewer", null, auth("admin-a"), redirect());

        assertThat(directory.find("admin-a").orElseThrow().role()).as("self-demotion allowed").isEqualTo(Role.VIEWER);
        assertThat(directory.find("admin-b").orElseThrow().role()).as("the other admin is untouched")
                .isEqualTo(Role.ADMIN);
    }

    @Test
    public void an_admin_may_demote_another_admin_even_when_that_leaves_themselves_the_last() throws IOException {
        directory.put("admin-a", Role.ADMIN, "a");
        directory.put("admin-b", Role.ADMIN, "b");

        // admin-a demotes admin-b: not a self-demotion, so it is allowed even though admin-a is now the last admin.
        controller.addUser("admin-b", "viewer", null, auth("admin-a"), redirect());

        assertThat(directory.find("admin-b").orElseThrow().role()).isEqualTo(Role.VIEWER);
    }

    private static Authentication auth(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_USER");
    }

    private static RedirectAttributesModelMap redirect() {
        return new RedirectAttributesModelMap();
    }
}
