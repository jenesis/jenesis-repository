package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsoleAdvice;
import build.jenesis.repository.ui.NavEntry;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's menu: the links installed modules contribute, and who sees each one.
 *
 * <p>The free console had no menu at all. A module could register its configuration - the seam worked - and its
 * screens were then reachable only by typing the path, which is not an extension point anybody can use. The links
 * are resolved here rather than in a template so the shell renders them with a loop and names no module's screens.
 */
class ConsoleNavEntriesTest {

    private final ConsoleAdvice advice = new ConsoleAdvice(new StandardEnvironment());

    private static Authentication user(String... roles) {
        return new TestingAuthenticationToken("octocat", "n/a", roles);
    }

    private List<String> labels(List<NavEntry> entries) {
        return entries.stream().map(NavEntry::label).toList();
    }

    @Test
    void a_signed_in_user_sees_the_links_open_to_everyone_and_no_others() {
        assertThat(labels(advice.navEntries(user("ROLE_USER"))))
                .as("the console's own screens lead the bar, then what this user may reach of the contributed ones")
                .containsExactly("Overview", "Everyone");
        assertThat(advice.adminNav(user("ROLE_USER")))
                .as("so the administration dropdown is empty and the layout renders none of it")
                .isEmpty();
    }

    @Test
    void an_admin_sees_the_admin_links_too_in_the_section_each_declared() {
        assertThat(labels(advice.navEntries(user("ROLE_USER", "ROLE_ADMIN"))))
                .as("primary keeps its own section only, so the bar does not swallow the dropdown's contents")
                .containsExactlyInAnyOrder("Overview", "Everyone", "Admins", "Operators");
        assertThat(labels(advice.adminNav(user("ROLE_USER", "ROLE_ADMIN"))))
                .as("the console's own administration screen sits with the contributed ones")
                .containsExactly("Installed providers", "Security posture", "Metrics", "Settings");
    }

    @Test
    void a_superadmin_link_resolves_to_admin_on_a_single_tenant_console() {
        // This console has two tiers and one tenant, so "administers this deployment" and "administers the tenant"
        // name the same person. Hiding a SUPERADMIN link instead would leave a module's own administration screen
        // unreachable on the very deployment that installed it.
        assertThat(labels(advice.navEntries(user("ROLE_USER", "ROLE_ADMIN")))).contains("Operators");
        assertThat(labels(advice.navEntries(user("ROLE_USER")))).doesNotContain("Operators");
    }

    @Test
    void an_unauthenticated_request_is_offered_nothing() {
        // The error page renders the shell outside any authenticated request, so this is a real path rather than a
        // hypothetical: it must answer an empty list rather than throw, and must not leak the menu's shape.
        assertThat(advice.navEntries(null)).isEmpty();
        assertThat(advice.adminNav(null)).isEmpty();
    }
}
