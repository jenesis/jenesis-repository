package build.jenesis.repository.posture.test;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the collected posture report does with an advisor that misbehaves. Two properties, and both are about the same
 * thing: on this surface silence means "checked, and clean", so neither a failure nor a collision may be resolved by
 * saying nothing.
 *
 * <ul>
 *   <li>An advisor that throws is contained to its own row - the header badge every console view renders and
 *       {@code GET /api/posture} both stand, every other advisor is still evaluated, and the failed advisor becomes a
 *       {@code jenesis.posture.unavailable.<advisor>} advisory saying that whatever it checks went unchecked.</li>
 *   <li>Two advisors raising one id are both kept and the clash is reported, because an id is the row key and the docs
 *       anchor; a tenant-scoped advisory raised for two tenants is not a clash.</li>
 * </ul>
 */
class PostureContainmentTest {

    private static final Configuration EMPTY = Configuration.ofMap(Map.of());

    private static SafetyAdvisor advisor(SecurityAdvisory... advisories) {
        return _ -> List.of(advisories);
    }

    private static SecurityAdvisory advisory(String id, Severity severity) {
        return SecurityAdvisory.deployment(id, severity, "title", "why", "fix", "key", "value", "docs");
    }

    @Test
    void a_throwing_advisor_is_contained_and_every_other_advisor_is_still_evaluated() {
        // Before containment this call threw straight out of from(), taking the console header badge (rendered on
        // EVERY console view) and GET /api/posture down with it.
        PostureReport report = PostureReport.from(List.of(
                new ThrowingSafetyAdvisor(),
                advisor(advisory("jenesis.auth.open", Severity.CRITICAL))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("the surviving advisor's advisory is reported, and the failed advisor is reported as failed")
                .containsExactly("jenesis.auth.open", "jenesis.posture.unavailable.throwingsafetyadvisor");
        assertThat(report.count()).as("the badge count rises rather than falls: a deployment whose posture is "
                + "partially unknown does not have less to worry about").isEqualTo(2);

        SecurityAdvisory substitute = report.advisories().stream()
                .filter(advisory -> advisory.id().startsWith("jenesis.posture.unavailable")).findFirst().orElseThrow();
        assertThat(substitute.severity()).isEqualTo(Severity.WARN);
        assertThat(substitute.scope()).isEqualTo(Scope.DEPLOYMENT);
        assertThat(substitute.why()).as("it names the advisor, the kind of failure, and that its silence is not an "
                        + "all-clear")
                .contains(ThrowingSafetyAdvisor.class.getName())
                .contains("IllegalStateException")
                .contains("NOT an all-clear");
        assertThat(substitute.why() + substitute.title() + substitute.fix())
                .as("the exception message never reaches a surface that enumerates the deployment's weaknesses - it "
                        + "goes to the log")
                .doesNotContain(ThrowingSafetyAdvisor.SECRET).doesNotContain("hunter2");
    }

    @Test
    void an_advisor_answering_null_is_contained_like_a_throw() {
        PostureReport report = PostureReport.from(List.of((SafetyAdvisor) _ -> null), EMPTY);
        assertThat(report.advisories()).singleElement()
                .satisfies(advisory -> assertThat(advisory.id()).startsWith("jenesis.posture.unavailable"));
    }

    @Test
    void an_error_is_not_contained() {
        // A LinkageError from a half-installed module is a broken module graph, not an advisor declining to answer.
        assertThatThrownBy(() -> PostureReport.from(List.of((SafetyAdvisor) _ -> {
            throw new LinkageError("half-installed module");
        }), EMPTY)).isInstanceOf(LinkageError.class);
    }

    @Test
    void two_advisors_raising_one_id_keep_both_rows_and_the_clash_is_reported() {
        // This SPI declares no name(), so it inherits no duplicate refusal from the shared provider primitives; the
        // collision that actually costs a reader something is the duplicated advisory id, and it is caught here.
        PostureReport report = PostureReport.from(List.of(
                advisor(advisory("jenesis.auth.open", Severity.CRITICAL)),
                advisor(advisory("jenesis.auth.open", Severity.INFO))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("both rows are kept - an id clash must never cost an operator a real advisory")
                .containsExactly("jenesis.auth.open", "jenesis.posture.collision", "jenesis.auth.open");
        SecurityAdvisory collision = report.advisories().stream()
                .filter(advisory -> advisory.id().equals("jenesis.posture.collision")).findFirst().orElseThrow();
        assertThat(collision.why()).contains("jenesis.auth.open");
        assertThat(collision.severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void one_advisory_raised_for_two_tenants_is_not_a_collision() {
        // The row key of a tenant-scoped advisory is its id AND its tenant: the same condition holding for two tenants
        // is two legitimate rows, and reporting that as a packaging error would train an operator to ignore the row.
        PostureReport report = PostureReport.from(List.of(advisor(
                SecurityAdvisory.tenant("jenesis.tenant.open", Severity.WARN, "acme", "t", "w", "f", "k", "v", "d"),
                SecurityAdvisory.tenant("jenesis.tenant.open", Severity.WARN, "globex", "t", "w", "f", "k", "v", "d"))),
                EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .containsExactly("jenesis.tenant.open", "jenesis.tenant.open");
        assertThat(report.advisories()).extracting(SecurityAdvisory::tenant).containsExactly("acme", "globex");
    }

    @Test
    void a_clean_report_gains_nothing_from_either_guard() {
        PostureReport report = PostureReport.from(List.of(
                advisor(advisory("jenesis.auth.open", Severity.CRITICAL)),
                advisor(advisory("jenesis.ui.admins", Severity.WARN))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("no failure and no clash means no synthetic row at all - an empty report stays the healthy state")
                .containsExactly("jenesis.auth.open", "jenesis.ui.admins");
    }
}
