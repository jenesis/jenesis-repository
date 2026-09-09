package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.ui.ScopedPosture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's tenant-posture containment boundary: <b>a tenant view must not show another tenant's
 * advisories</b>, and must still show the deployment-wide ones.
 *
 * <p>This is the leg that fails if the scoping is dropped. The screen could plausibly be written as "render every
 * {@code TENANT}-scoped row the report carries" - which reads identically on a report collected for one tenant, and
 * is a cross-tenant disclosure the moment a report carries rows for two. So the report here <em>does</em> carry rows
 * for two tenants: a collection over two advisors, each naming its own. Nothing in the {@code SafetyAdvisor} SPI
 * forbids that (an advisory names its tenant itself, and a report is an open fan-out over discovered providers), so
 * the guarantee cannot live in the advisors - it has to live where the rows are selected, which is
 * {@link ScopedPosture} calling {@link PostureReport#forTenant}.
 *
 * <p>The counts are asserted for the same reason as the rows: a tally computed over {@code report.count()} rather
 * than over what is rendered would leak the <em>existence</em> of another tenant's advisories as a number, which is
 * exactly the kind of "not really a disclosure" that is one.
 */
class ScopedPostureTest {

    private static final SecurityAdvisory ACME = SecurityAdvisory.tenant("jenreg.gate.malware", Severity.CRITICAL,
            "acme", "acme admits malicious packages", "acme why", "acme fix",
            "jenreg.malware-action", "QUARANTINE", "");

    private static final SecurityAdvisory GLOBEX = SecurityAdvisory.tenant("jenreg.gate.vulnerability", Severity.WARN,
            "globex", "globex has its vulnerability check disabled", "globex why", "globex fix",
            "jenreg.vulnerability-threshold", "CRITICAL", "");

    private static final SecurityAdvisory DEPLOYMENT = SecurityAdvisory.deployment("jenreg.auth.open",
            Severity.CRITICAL, "Authorization is disabled", "deployment why", "deployment fix",
            "jenreg.auth", "true", "");

    /** A report carrying both tenants' rows and a deployment-wide one - the shape a fan-out over several advisors
     *  produces, and the only shape in which dropped scoping is observable. */
    private static PostureReport twoTenants() {
        List<SafetyAdvisor> advisors = List.of(
                _ -> List.of(ACME), _ -> List.of(GLOBEX), _ -> List.of(DEPLOYMENT));
        return PostureReport.from(advisors, Configuration.ofMap(Map.of()));
    }

    @Test
    void a_tenants_view_shows_its_own_advisories_and_not_another_tenants() {
        ScopedPosture view = ScopedPosture.of(twoTenants(), "acme");
        assertThat(view.own()).extracting(SecurityAdvisory::id)
                .as("this tenant's own row is shown").containsExactly("jenreg.gate.malware");
        assertThat(view.own()).extracting(SecurityAdvisory::tenant)
                .as("and every row in the tenant half belongs to the tenant being viewed").containsOnly("acme");
        assertThat(view.rendered()).extracting(SecurityAdvisory::id)
                .as("another tenant's advisory is in neither half of what is rendered")
                .doesNotContain("jenreg.gate.vulnerability");
        assertThat(view.rendered()).extracting(SecurityAdvisory::tenant)
                .as("and no rendered row names a tenant other than this one").doesNotContain("globex");
    }

    @Test
    void the_other_tenants_view_shows_the_other_advisory_so_neither_is_simply_hidden() {
        // The mirror image, so the assertion above cannot be satisfied by rendering nothing at all: the row exists,
        // it is real, and it appears - in the one view it belongs to.
        ScopedPosture view = ScopedPosture.of(twoTenants(), "globex");
        assertThat(view.own()).extracting(SecurityAdvisory::id).containsExactly("jenreg.gate.vulnerability");
        assertThat(view.rendered()).extracting(SecurityAdvisory::id).doesNotContain("jenreg.gate.malware");
    }

    @Test
    void a_deployment_wide_advisory_reaches_every_tenants_view() {
        for (String tenant : List.of("acme", "globex")) {
            ScopedPosture view = ScopedPosture.of(twoTenants(), tenant);
            assertThat(view.deployment()).extracting(SecurityAdvisory::id)
                    .as("a deployment-wide row applies to every tenant and is shown in each view")
                    .containsExactly("jenreg.auth.open");
            assertThat(view.own()).extracting(SecurityAdvisory::scope)
                    .as("the two halves are kept apart so an operator can tell whose each row is")
                    .containsOnly(build.jenesis.repository.posture.Scope.TENANT);
        }
    }

    @Test
    void the_tallies_count_what_is_rendered_and_never_another_tenants_rows() {
        ScopedPosture view = ScopedPosture.of(twoTenants(), "acme");
        assertThat(view.count()).as("one tenant row plus one deployment row - not the report's three").isEqualTo(2);
        assertThat(view.critical()).as("both rendered rows are critical").isEqualTo(2);
        assertThat(view.warn()).as("the only WARN in the report belongs to the other tenant and is not counted")
                .isZero();
        assertThat(view.info()).isZero();
        assertThat(view.empty()).isFalse();
    }

    @Test
    void a_session_with_no_tenant_degrades_to_the_deployment_wide_view() {
        for (String none : Arrays.asList(null, "", "   ")) {
            ScopedPosture view = ScopedPosture.of(twoTenants(), none);
            assertThat(view.scoped()).as("no tenant is selected, so the screen shows no tenant section").isFalse();
            assertThat(view.own()).as("and guesses no tenant's rows").isEmpty();
            assertThat(view.rendered()).extracting(SecurityAdvisory::id)
                    .as("the deployment-wide view it was before still renders")
                    .containsExactly("jenreg.auth.open");
        }
    }

    @Test
    void a_single_tenant_deployment_is_the_implicit_tenant_rather_than_a_special_case() {
        // Nothing here branches on how many tenants exist: the one accessible tenant is selected by the session, so
        // the same split applies and the tenant's own rows render exactly as they would beside ninety-nine others.
        PostureReport report = PostureReport.from(List.<SafetyAdvisor>of(_ -> List.of(ACME, DEPLOYMENT)),
                Configuration.ofMap(Map.of()));
        ScopedPosture view = ScopedPosture.of(report, "acme");
        assertThat(view.scoped()).isTrue();
        assertThat(view.own()).extracting(SecurityAdvisory::id).containsExactly("jenreg.gate.malware");
        assertThat(view.deployment()).extracting(SecurityAdvisory::id).containsExactly("jenreg.auth.open");
        assertThat(view.count()).isEqualTo(2);
    }

    @Test
    void a_clean_report_is_the_healthy_empty_state() {
        ScopedPosture view = ScopedPosture.of(new PostureReport(List.of()), "acme");
        assertThat(view.empty()).isTrue();
        assertThat(view.count()).isZero();
        assertThat(view.scoped()).as("a tenant is still selected - there is simply nothing to say about it").isTrue();
    }
}
