package build.jenesis.repository.ui.identity.test;

import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.ConsoleAdministrators;
import module java.base;

import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.identity.Superadmins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The deployment's super-admins, and the one value this console refuses to read.
 *
 * <p>{@code jenreg.ui.admins} is shared with the single-tenant console, where {@code *} is a documented opt-out
 * granting admin to every authenticated user. A super-admin here is not that: it administers <em>every</em> tenant
 * and the deployment itself, so the same wildcard would be a far larger grant, and not one anybody sets on purpose.
 *
 * <p>It used to be neither honoured nor refused - {@code *} was kept as a literal id, which matches no principal, so
 * a deployment configured that way had <em>no</em> super-admins at all while the security advisory told the operator
 * every signed-in user was one. Both directions wrong, and silently. Refusing at startup is the only answer that
 * cannot mislead: the operator is told, once, before anything runs.
 */
class SuperadminsTest {

    @TempDir
    Path root;

    private Superadmins over(String configured) {
        return over(configured, "multi");
    }

    /**
     * A super-admin set built the way the console builds it: the configured ids seeded as deployment-wide grants,
     * and the answer read back from those grants. The tenancy argument survives only so the refusal cases can
     * assert it is refused under every routing - it no longer reaches anything, which is the point.
     */
    private Superadmins over(String configured, String tenancy) {
        UiProperties properties = new UiProperties();
        properties.setAdmins(configured);
        return new Superadmins(new ConsoleAdministrators(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.resolve(tenancy).toString() : null), properties.getAdmins()));
    }

    @Test
    void named_ids_are_super_admins_and_nobody_else_is() {
        Superadmins superadmins = over(" github/1 , oidc/abc ");
        assertThat(superadmins.is("github/1")).isTrue();
        assertThat(superadmins.is("oidc/abc")).as("trimmed, as any configured list is").isTrue();
        assertThat(superadmins.is("github/2")).isFalse();
    }

    @Test
    void an_unset_list_grants_nobody() {
        assertThat(over("").is("github/1")).as("the secure default: no super-admin rather than every user").isFalse();
    }

    @Test
    void the_wildcard_is_refused_whatever_the_tenancy() {
        // It used to be honoured under fixed tenancy - where "administers every tenant" and "administers the one
        // tenant" are the same grant, so it was the single-tenant console's documented open-console opt-out - and
        // refused under any multi-tenant routing. It is refused everywhere now and the tenancy mode no longer
        // enters into it: an administrator is a holder of rights, and a wildcard names no holder, so nothing is
        // granted that an operator could read back, revoke, or see in a list of who administers this deployment.
        for (String tenancy : List.of("fixed", "multi", "host", "path")) {
            assertThatIllegalStateException()
                    .as("refused under %s", tenancy)
                    .isThrownBy(() -> over("*", tenancy))
                    .withMessageContaining("names no holder")
                    .withMessageContaining("github/<id>");
        }
        assertThatIllegalStateException()
                .as("including a wildcard hidden among named ids, which is the shape a whole-value check misses")
                .isThrownBy(() -> over("github/1,*"));
    }

}
