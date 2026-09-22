package build.jenesis.repository.ui.admin.test;

import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.admin.web.AdminController;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console is the third surface over the group primitive, and it reaches the same implementation the other two
 * do.
 *
 * <p>That is the claim worth testing rather than the markup. {@code SurfaceParityRule} measures a shared capability
 * by the code two surfaces reach, so a screen that called a service of this console's own would be a second
 * implementation of "grant a group rights" - and the two would answer differently the day one of them learned
 * something the other did not. Here the screen writes through {@link Authorization}, and what it wrote is read back
 * through {@code authorize}, which is the same question a request asks.
 */
public class GroupAdministrationTest {

    @TempDir
    private Path root;

    private Authorization authorization;

    private AdminController controller;

    @BeforeEach
    public void setUp() {
        Documents documents = CacheStorages.documents(root);
        authorization = Authorization.enforcing(documents.store());
        controller = new AdminController(new UserDirectory(authorization, "acme"),
                new KnownPrincipals(authorization), documents.scope("acme"), AuditTrail.none(),
                () -> "acme", () -> "root", authorization);
    }

    @Test
    public void a_grant_made_on_the_screen_is_one_a_member_really_holds() throws IOException {
        controller.addGroupMember("developers", "oidc/ada", redirect());
        controller.grantGroup("developers", "*", Authorization.REPOSITORY_READ, redirect());

        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("what the screen wrote is what a request is authorized against").isTrue();
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_WRITE))
                .as("and only that").isFalse();
    }

    @Test
    public void revoking_on_the_screen_takes_it_back_from_everyone_who_held_it() throws IOException {
        controller.addGroupMember("developers", "oidc/ada", redirect());
        controller.grantGroup("developers", "*", Authorization.REPOSITORY_READ, redirect());

        controller.revokeGroupGrant("developers", "*", redirect());
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("a revoke that only removed a row would leave the member holding it").isFalse();

        controller.grantGroup("developers", "*", Authorization.REPOSITORY_READ, redirect());
        controller.removeGroupMember("developers", "oidc/ada", redirect());
        assertThat(allowed("oidc/ada", Authorization.REPOSITORY_READ))
                .as("and offboarding one member is the other half of it").isFalse();
    }

    @Test
    public void the_screen_renders_what_a_group_grants_and_a_bounded_preview_of_who_is_in_it() throws IOException {
        controller.grantGroup("developers", "*", Authorization.REPOSITORY_READ, redirect());
        for (int at = 0; at < 12; at++) {
            controller.addGroupMember("developers", "oidc/person-" + at, redirect());
        }

        Model model = new ExtendedModelMap();
        controller.admin(null, model);

        @SuppressWarnings("unchecked")
        List<AdminController.GroupRow> rows = (List<AdminController.GroupRow>) model.getAttribute("groups");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("developers");
            assertThat(row.grants()).containsEntry("*", Authorization.REPOSITORY_READ);
            // The preview is capped, so this screen costs the same on a group of eight and one of eight hundred -
            // and it says so rather than rendering a truncated list as if it were the membership.
            assertThat(row.members()).hasSizeLessThan(12);
            assertThat(row.more()).as("a capped preview that did not say so would read as the whole group").isTrue();
        });
    }

    private boolean allowed(String principal, String right) throws IOException {
        return authorization.authorize("acme", Authorization.Subject.principal(principal), null, right)
                == Authorization.Decision.ALLOWED;
    }

    private static RedirectAttributesModelMap redirect() {
        return new RedirectAttributesModelMap();
    }
}
