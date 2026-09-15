package build.jenesis.repository.format.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.Features;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RepositoryFormat#installed()} answers the same thing every time it is asked, so it is asked once.
 *
 * <p>The set of formats a deployment serves is a function of its configuration, and the configuration is installed
 * once at boot - so re-deriving the set is work with a known answer, paid on every call. It is asked on
 * per-request and per-artifact paths (an inventory describing a path, the compliance screen deciding whose prefix a
 * publish is under), and a flight recording of a five-minute soak found it the hottest product frame in the run:
 * 262 of 20,707 execution samples, about 1.3% of sampled CPU in one method.
 *
 * <p>Both halves are asserted here, because holding an answer is only safe while it is still the answer: the same
 * lookup gives the same list, and a lookup that says something different gives a different one.
 */
class InstalledFormatsTest {

    @AfterEach
    void restoreTheDefaultLookup() {
        Features.reset();
    }

    @Test
    void the_active_set_is_derived_once_for_a_configuration() {
        Features.reset();
        List<RepositoryFormat> first = RepositoryFormat.installed();
        assertThat(RepositoryFormat.installed())
                .as("the configuration has not moved, so neither has the answer - and the work of deriving it is "
                        + "paid on paths that run per artifact")
                .isSameAs(first);
    }

    @Test
    void a_reconfigured_deployment_is_asked_again() {
        Features.reset();
        List<RepositoryFormat> before = RepositoryFormat.installed();
        assertThat(before).extracting(RepositoryFormat::name)
                .as("the stub format this module registers is on by default, which is what the next step turns off")
                .contains("twin-alpha");

        Features.configure(key -> "jenreg.twin-alpha".equals(key) ? "false" : null);
        List<RepositoryFormat> after = RepositoryFormat.installed();
        assertThat(after).extracting(RepositoryFormat::name)
                .as("a format configured off is absent exactly as a missing module is - a held answer that "
                        + "outlived the configuration that produced it would serve a format the operator switched off")
                .doesNotContain("twin-alpha");
        assertThat(after).as("and it really is a fresh answer rather than the same list").isNotSameAs(before);

        Features.reset();
        assertThat(RepositoryFormat.installed()).extracting(RepositoryFormat::name)
                .as("and back again, so the hold is keyed on the configuration rather than latched at first use")
                .contains("twin-alpha");
    }
}
