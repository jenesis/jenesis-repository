package build.jenesis.repository.posture.test;

import build.jenesis.repository.posture.ConsoleAdmins;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one rule for reading {@code jenreg.ui.admins}, which three readers had each written for themselves.
 *
 * <p>They had already diverged: the console's authority policy honoured {@code *}, the deployment's super-admin set
 * treated it as a literal id matching nobody, and the advisory about the key parsed it a third time with a comment
 * claiming to match the first. One documented key meant "everyone is an admin" in one console and "nobody is" in
 * another, while the advisory asserted the first regardless of which was running.
 */
class ConsoleAdminsTest {

    @Test
    void the_list_is_trimmed_and_empties_are_dropped() {
        assertThat(ConsoleAdmins.parse(" github/1 , oidc/abc ")).containsExactly("github/1", "oidc/abc");
        assertThat(ConsoleAdmins.parse("github/1,,oidc/abc,")).as("a trailing or doubled comma names nobody")
                .containsExactly("github/1", "oidc/abc");
        assertThat(ConsoleAdmins.parse("")).isEmpty();
        assertThat(ConsoleAdmins.parse("   ")).isEmpty();
        assertThat(ConsoleAdmins.parse(null)).as("an unset key grants nobody, which is the secure default").isEmpty();
    }

    @Test
    void the_order_configured_is_the_order_kept() {
        assertThat(ConsoleAdmins.parse("c,a,b")).containsExactly("c", "a", "b");
    }

    @Test
    void the_wildcard_counts_wherever_it_appears_in_the_list() {
        assertThat(ConsoleAdmins.grantsEveryone(ConsoleAdmins.parse("*"))).isTrue();
        assertThat(ConsoleAdmins.grantsEveryone(ConsoleAdmins.parse("github/1,*")))
                .as("a wildcard hidden among named ids still grants everyone, so a reader that matched only the "
                        + "whole value would fail open on it")
                .isTrue();
        assertThat(ConsoleAdmins.grantsEveryone(ConsoleAdmins.parse(" * ")))
                .as("and it is recognised after trimming, as any other entry is")
                .isTrue();

        assertThat(ConsoleAdmins.grantsEveryone(ConsoleAdmins.parse("github/1"))).isFalse();
        assertThat(ConsoleAdmins.grantsEveryone(ConsoleAdmins.parse(""))).isFalse();
    }
}
