package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.ui.Principals;
import build.jenesis.repository.ui.UiProperties;
import org.springframework.security.core.GrantedAuthority;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The console's authority model is deny-by-default: an unconfigured {@code jenreg.ui.admins} grants {@code ROLE_USER}
 * but never {@code ROLE_ADMIN}, so an unconfigured deployment denies writes (a POST/PUT/DELETE needs {@code ADMIN})
 * rather than handing full admin to whoever signs in. A configured id still becomes an admin, and the {@code *}
 * wildcard is the explicit opt-out that re-opens the console to every authenticated user.
 */
class PrincipalsTest {

    @Test
    void an_empty_admins_list_grants_no_admin_so_writes_are_denied_by_default() {
        List<String> roles = roles(principals(""), "oidc/anyone");
        assertThat(roles).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void a_configured_admin_is_granted_admin_and_others_are_not() {
        Principals principals = principals("oidc/alice, github/bob");
        assertThat(roles(principals, "oidc/alice")).contains("ROLE_USER", "ROLE_ADMIN");
        assertThat(roles(principals, "github/bob")).contains("ROLE_ADMIN");
        assertThat(roles(principals, "oidc/mallory")).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void the_wildcard_is_refused_rather_than_opening_the_console_to_everyone() {
        // It used to opt every authenticated user into ADMIN - the single-tenant console's open-console opt-out.
        // An administrator is a holder of rights and a wildcard names no holder, so there was nothing an operator
        // could read back, revoke, or see in a list of who administers this deployment. Refused rather than
        // ignored: ignoring fails in both directions, since the operator believes they granted something while in
        // fact nobody holds admin.
        assertThatIllegalStateException()
                .isThrownBy(() -> principals("*"))
                .withMessageContaining("names no holder");
        assertThatIllegalStateException()
                .as("and among named ids too, which is the spelling a whole-value check would miss")
                .isThrownBy(() -> principals("github/1, *"))
                .withMessageContaining("names no holder");
    }

    private static Principals principals(String admins) {
        UiProperties properties = new UiProperties();
        properties.setAdmins(admins);
        return new Principals(properties);
    }

    private static List<String> roles(Principals principals, String id) {
        return principals.authorities(id, "").stream().map(GrantedAuthority::getAuthority).toList();
    }
}
