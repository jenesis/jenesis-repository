package build.jenesis.repository.ui.test;

import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.ui.PostureScreenController;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the security-posture screen puts in front of an operator.
 *
 * <p>The panel this replaces asserted on generated HTML, so a change to the markup broke tests about the reading
 * and a change to the reading could hide behind markup that still contained the right words. The counts and the
 * scoping are the reading; the template renders them and is asserted where templates are - in the browser suite.
 */
class PostureViewTest {

    private static SecurityAdvisory advisory(String signal, Severity severity) {
        // Ids are jenreg.<feature>.<signal> shaped and the constructor enforces it, so the fixture uses real ones.
        String id = "jenreg.console." + signal;
        return SecurityAdvisory.deployment(id, severity, "Title " + signal, "Why " + signal, "Fix " + signal,
                "jenreg.console." + signal, "safe", "");
    }

    @Test
    void it_counts_each_severity_so_the_summary_and_the_list_cannot_disagree() {
        PostureReport report = new PostureReport(List.of(
                advisory("a", Severity.CRITICAL), advisory("b", Severity.WARN),
                advisory("c", Severity.WARN), advisory("d", Severity.INFO)));

        PostureScreenController.View view = PostureScreenController.View.of(report);

        assertThat(view.critical()).isEqualTo(1);
        assertThat(view.warn()).isEqualTo(2);
        assertThat(view.info()).isEqualTo(1);
        assertThat(view.advisories()).as("and the rows are the deployment-scoped ones the screen lists").hasSize(4);
    }

    @Test
    void a_clean_deployment_has_nothing_to_show_rather_than_an_empty_table() {
        PostureScreenController.View view = PostureScreenController.View.of(new PostureReport(List.of()));

        assertThat(view.advisories()).isEmpty();
        assertThat(view.critical() + view.warn() + view.info())
                .as("so the screen renders its 'no advisories' state instead of a zeroed summary")
                .isZero();
    }

    @Test
    void it_lists_the_deployment_scope_only() {
        PostureReport report = new PostureReport(List.of(advisory("a", Severity.WARN)));

        assertThat(PostureScreenController.View.of(report).advisories())
                .as("a tenant-scoped advisory belongs to a tenant's own view, not the deployment screen")
                .containsExactlyElementsOf(report.scoped(Scope.DEPLOYMENT));
    }
}
