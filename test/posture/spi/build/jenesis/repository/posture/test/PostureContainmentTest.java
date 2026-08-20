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
 *       {@code jenreg.posture.unavailable.<advisor>} advisory saying that whatever it checks went unchecked.</li>
 *   <li>Two advisors raising one id are both kept and the clash is reported, because an id is the row key and the docs
 *       anchor; a tenant-scoped advisory raised for two tenants is not a clash.</li>
 *   <li>And the clash is reported <em>at the scope of the rows that collided</em> (D-150), so saying that two rows
 *       collided never tells a viewer something about a tenant that is not theirs.</li>
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
                advisor(advisory("jenreg.auth.open", Severity.CRITICAL))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("the surviving advisor's advisory is reported, and the failed advisor is reported as failed")
                .containsExactly("jenreg.auth.open", "jenreg.posture.unavailable.throwingsafetyadvisor");
        assertThat(report.count()).as("the badge count rises rather than falls: a deployment whose posture is "
                + "partially unknown does not have less to worry about").isEqualTo(2);

        SecurityAdvisory substitute = report.advisories().stream()
                .filter(advisory -> advisory.id().startsWith("jenreg.posture.unavailable")).findFirst().orElseThrow();
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
                .satisfies(advisory -> assertThat(advisory.id()).startsWith("jenreg.posture.unavailable"));
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
                advisor(advisory("jenreg.auth.open", Severity.CRITICAL)),
                advisor(advisory("jenreg.auth.open", Severity.INFO))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("both rows are kept - an id clash must never cost an operator a real advisory")
                .containsExactly("jenreg.auth.open", "jenreg.posture.collision", "jenreg.auth.open");
        SecurityAdvisory collision = report.advisories().stream()
                .filter(advisory -> advisory.id().equals("jenreg.posture.collision")).findFirst().orElseThrow();
        assertThat(collision.why()).contains("jenreg.auth.open");
        assertThat(collision.severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void one_advisory_raised_for_two_tenants_is_not_a_collision() {
        // The row key of a tenant-scoped advisory is its id AND its tenant: the same condition holding for two tenants
        // is two legitimate rows, and reporting that as a packaging error would train an operator to ignore the row.
        PostureReport report = PostureReport.from(List.of(advisor(
                SecurityAdvisory.tenant("jenreg.tenant.open", Severity.WARN, "acme", "t", "w", "f", "k", "v", "d"),
                SecurityAdvisory.tenant("jenreg.tenant.open", Severity.WARN, "globex", "t", "w", "f", "k", "v", "d"))),
                EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .containsExactly("jenreg.tenant.open", "jenreg.tenant.open");
        assertThat(report.advisories()).extracting(SecurityAdvisory::tenant).containsExactly("acme", "globex");
    }

    /**
     * D-150: a clash between one tenant's rows is that tenant's row, not a deployment-wide row carrying its name.
     * Before this landed, {@code collisions} keyed a tenant row as {@code "<id> (tenant <name>)"} and interpolated
     * that key into a {@code SecurityAdvisory.deployment(...)} message - so a fan-out raising rows for two tenants
     * put one tenant's name and one advisory id into the deployment-wide row that {@code ScopedPosture} and
     * {@code GET /api/admin/posture} hand to <em>every</em> viewer. Every assertion below fails on that shape.
     */
    @Test
    void a_clash_between_one_tenants_rows_is_that_tenants_row_and_names_no_tenant_to_anyone_else() {
        PostureReport report = PostureReport.from(List.of(
                advisor(SecurityAdvisory.tenant("jenreg.tenant.open", Severity.WARN, "acme",
                                "t", "w", "f", "k", "v", "d"),
                        SecurityAdvisory.tenant("jenreg.tenant.open", Severity.INFO, "acme",
                                "t", "w", "f", "k", "v", "d"),
                        SecurityAdvisory.tenant("jenreg.tenant.open", Severity.WARN, "globex",
                                "t", "w", "f", "k", "v", "d"))), EMPTY);

        SecurityAdvisory collision = report.advisories().stream()
                .filter(advisory -> advisory.id().equals("jenreg.posture.collision")).findFirst().orElseThrow();
        assertThat(collision.scope()).as("the clash is between acme's rows, so it is filed at acme's scope")
                .isEqualTo(Scope.TENANT);
        assertThat(collision.tenant()).isEqualTo("acme");
        assertThat(collision.why()).as("and it still says WHAT collided - a collision report that cannot name the "
                + "clashing id is not a report").contains("jenreg.tenant.open");
        assertThat(collision.why()).as("the tenant rides the row's scope, never its text").doesNotContain("acme");

        assertThat(report.scoped(Scope.DEPLOYMENT)).extracting(SecurityAdvisory::id)
                .as("no collision row reaches the deployment-wide read - which is the read a headless operator and "
                        + "every other tenant's console view are served")
                .doesNotContain("jenreg.posture.collision");
        assertThat(report.forTenant("acme")).extracting(SecurityAdvisory::id)
                .as("acme, whose rows collided, is told").contains("jenreg.posture.collision");
        assertThat(report.forTenant("globex")).extracting(SecurityAdvisory::id)
                .as("globex, whose one row is legitimate, is not")
                .doesNotContain("jenreg.posture.collision");
        // The leak in one line: nothing another tenant or the deployment-wide reader is handed mentions acme at all.
        assertThat(Stream.concat(report.scoped(Scope.DEPLOYMENT).stream(), report.forTenant("globex").stream())
                .flatMap(advisory -> Stream.of(advisory.title(), advisory.why(), advisory.fix(), advisory.tenant())))
                .as("a tenant name never reaches a viewer who is not that tenant")
                .noneMatch(text -> text.contains("acme"));
    }

    @Test
    void two_tenants_that_each_have_a_clash_get_one_row_each_and_neither_names_the_other() {
        PostureReport report = PostureReport.from(List.of(
                advisor(SecurityAdvisory.tenant("jenreg.tenant.open", Severity.WARN, "acme",
                                "t", "w", "f", "k", "v", "d"),
                        SecurityAdvisory.tenant("jenreg.tenant.open", Severity.INFO, "acme",
                                "t", "w", "f", "k", "v", "d"),
                        SecurityAdvisory.tenant("jenreg.tenant.gate", Severity.WARN, "globex",
                                "t", "w", "f", "k", "v", "d"),
                        SecurityAdvisory.tenant("jenreg.tenant.gate", Severity.INFO, "globex",
                                "t", "w", "f", "k", "v", "d"))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("both tenants' clashes are reported - filing at tenant scope loses no diagnosis")
                .filteredOn("jenreg.posture.collision"::equals).hasSize(2);
        assertThat(report.forTenant("acme")).filteredOn(advisory ->
                        advisory.id().equals("jenreg.posture.collision"))
                .singleElement()
                .satisfies(advisory -> {
                    assertThat(advisory.why()).contains("jenreg.tenant.open");
                    assertThat(advisory.why()).as("acme is not told which id globex duplicated")
                            .doesNotContain("jenreg.tenant.gate").doesNotContain("globex");
                });
        assertThat(report.forTenant("globex")).filteredOn(advisory ->
                        advisory.id().equals("jenreg.posture.collision"))
                .singleElement()
                .satisfies(advisory -> {
                    assertThat(advisory.why()).contains("jenreg.tenant.gate");
                    assertThat(advisory.why()).doesNotContain("jenreg.tenant.open").doesNotContain("acme");
                });
    }

    @Test
    void a_deployment_clash_and_a_tenant_clash_are_two_rows_at_two_scopes() {
        PostureReport report = PostureReport.from(List.of(
                advisor(advisory("jenreg.auth.open", Severity.CRITICAL),
                        advisory("jenreg.auth.open", Severity.INFO),
                        SecurityAdvisory.tenant("jenreg.tenant.open", Severity.WARN, "acme",
                                "t", "w", "f", "k", "v", "d"),
                        SecurityAdvisory.tenant("jenreg.tenant.open", Severity.INFO, "acme",
                                "t", "w", "f", "k", "v", "d"))), EMPTY);

        assertThat(report.scoped(Scope.DEPLOYMENT)).filteredOn(advisory ->
                        advisory.id().equals("jenreg.posture.collision"))
                .singleElement()
                .satisfies(advisory -> {
                    assertThat(advisory.why()).as("the deployment-wide clash is named deployment-wide")
                            .contains("jenreg.auth.open");
                    assertThat(advisory.why()).as("and carries nothing of the tenant clash")
                            .doesNotContain("jenreg.tenant.open").doesNotContain("acme");
                });
        assertThat(report.forTenant("acme")).filteredOn(advisory ->
                        advisory.id().equals("jenreg.posture.collision"))
                .singleElement()
                .satisfies(advisory -> assertThat(advisory.why()).contains("jenreg.tenant.open"));
    }

    @Test
    void a_clean_report_gains_nothing_from_either_guard() {
        PostureReport report = PostureReport.from(List.of(
                advisor(advisory("jenreg.auth.open", Severity.CRITICAL)),
                advisor(advisory("jenreg.ui.admins", Severity.WARN))), EMPTY);

        assertThat(report.advisories()).extracting(SecurityAdvisory::id)
                .as("no failure and no clash means no synthetic row at all - an empty report stays the healthy state")
                .containsExactly("jenreg.auth.open", "jenreg.ui.admins");
    }
}
