package build.jenesis.repository.ui.test;

import module java.base;

import build.jenesis.repository.ui.PostureSource;
import build.jenesis.repository.ui.ConsoleAdvice;
import build.jenesis.repository.ui.NavEntry;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's menu: the links installed modules contribute, and who sees each one.
 *
 * <p>The free console had no menu at all. A module could register its configuration - the seam worked - and its
 * screens were then reachable only by typing the path, which is not an extension point anybody can use. The links
 * are resolved here rather than in a template so the shell renders them with a loop and names no module's screens.
 */
class ConsoleNavEntriesTest {

    private static final StandardEnvironment ENVIRONMENT = new StandardEnvironment();

    private final ConsoleAdvice advice = new ConsoleAdvice(ENVIRONMENT,
            PostureSource.ofEnvironment(ENVIRONMENT::getProperty), () -> "default", List.of());

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

    /**
     * The nav is discovered when the advice is built, and never again - the lifecycle clause of
     * {@code ConsoleModuleProvider}'s contract, made executable.
     *
     * <p>It was not true when this was written. {@code entries} asked {@code ConsoleModuleProvider.enabled} on
     * every render, and twice on each, since the bar and the administration dropdown are two model attributes over
     * one fan-out: every page walked the module graph's service declarations, re-instantiated every installed provider,
     * re-sorted them and rebuilt the duplicate-name and duplicate-class maps that make a packaging error throw.
     * Nothing was wrong with the answer, which is why nothing was red - the cost was the defect, and a contract
     * clause stating the opposite was the only thing that said so.
     *
     * <p>Counting asks rather than timing anything is deliberate: a timing assertion on a saturated machine is the
     * flake this repository keeps having to remove, and the claim here is not "it is fast" but "it happens once".
     */
    @Test
    void the_contributed_nav_is_discovered_once_and_never_on_the_request_path() {
        NavigatingConsoleModule.forget();
        ConsoleAdvice built = new ConsoleAdvice(ENVIRONMENT,
                PostureSource.ofEnvironment(ENVIRONMENT::getProperty), () -> "default", List.of());
        int afterConstruction = NavigatingConsoleModule.asked();
        assertThat(afterConstruction)
                .as("building the advice is what discovers the modules, so the fan-out happens here or nowhere")
                .isPositive();

        for (int render = 0; render < 25; render++) {
            built.navEntries(user("ROLE_USER", "ROLE_ADMIN"));
            built.adminNav(user("ROLE_USER", "ROLE_ADMIN"));
        }
        assertThat(NavigatingConsoleModule.asked())
                .as("fifty renders asked the installed modules nothing further; a module re-asked per render is "
                        + "the whole module graph walked per page, which is what the clause forbids")
                .isEqualTo(afterConstruction);
    }

    @Test
    void an_unauthenticated_request_is_offered_nothing() {
        // The error page renders the shell outside any authenticated request, so this is a real path rather than a
        // hypothetical: it must answer an empty list rather than throw, and must not leak the menu's shape.
        assertThat(advice.navEntries(null)).isEmpty();
        assertThat(advice.adminNav(null)).isEmpty();
    }
}
