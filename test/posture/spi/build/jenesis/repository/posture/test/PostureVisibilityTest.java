package build.jenesis.repository.posture.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a caller may be shown, on the surface whose whole purpose is to enumerate a deployment's weaknesses.
 *
 * <p>A read that renders {@link PostureReport#advisories()} whole hands one tenant's unsafe settings to every
 * other tenant's readers. That is not hypothetical: the free {@code GET /api/posture} rendered the report entire
 * to any {@code repository:read} caller, while the downstream console composed the deployment rows and the
 * caller's own tenant rows correctly one edition over - the same two calls, made in one place and not the other.
 * {@link PostureReport#visibleTo} is that composition, moved to where both surfaces reach it.
 *
 * <p>The interesting direction is the negative one. A leak is a row appearing that should not, so every leg below
 * asserts what is <em>absent</em> as well as what is present, and the anonymous case is checked separately because
 * "no tenant" must resolve to the deployment rows rather than to all of them.
 */
class PostureVisibilityTest {

    private static final Configuration EMPTY = Configuration.ofMap(Map.of());

    private static SafetyAdvisor advisor(SecurityAdvisory... advisories) {
        return _ -> List.of(advisories);
    }

    private static SecurityAdvisory deployment(String id) {
        return SecurityAdvisory.deployment(id, Severity.WARN, "title", "why", "fix", "key", "value", "docs");
    }

    private static SecurityAdvisory tenant(String id, String tenant) {
        return SecurityAdvisory.tenant(id, Severity.WARN, tenant, "title", "why", "fix", "key", "value", "docs");
    }

    private static PostureReport report() {
        return PostureReport.from(List.of(advisor(
                deployment("jenreg.auth.open"),
                tenant("jenreg.tls.acme", "acme"),
                tenant("jenreg.tls.globex", "globex"))), EMPTY);
    }

    private static List<String> ids(List<SecurityAdvisory> advisories) {
        return advisories.stream().map(SecurityAdvisory::id).toList();
    }

    @Test
    void a_tenants_caller_sees_the_deployment_rows_and_only_its_own() {
        assertThat(ids(report().visibleTo("acme")))
                .as("its own tenant row and every deployment-wide one")
                .containsExactlyInAnyOrder("jenreg.auth.open", "jenreg.tls.acme");
    }

    @Test
    void a_tenants_caller_never_sees_another_tenants_row() {
        assertThat(ids(report().visibleTo("acme")))
                .as("the leak: another tenant's unsafe settings, on a surface that names the setting and its value")
                .doesNotContain("jenreg.tls.globex");
        assertThat(ids(report().visibleTo("globex")))
                .as("and symmetrically, so this is not passing by an accident of ordering")
                .containsExactlyInAnyOrder("jenreg.auth.open", "jenreg.tls.globex");
    }

    @Test
    void a_caller_with_no_tenant_sees_the_deployment_rows_alone() {
        // An anonymous read, or a key that belongs to no tenant. Resolving "no tenant" to "everything" is the
        // failure mode worth naming, because it reads as permissive rather than as a bug.
        for (String none : new String[]{null, "", "   "}) {
            assertThat(ids(report().visibleTo(none)))
                    .as("a caller with no tenant (%s) gets the deployment rows and nothing tenant-scoped",
                            none == null ? "null" : "'" + none + "'")
                    .containsExactly("jenreg.auth.open");
        }
    }

    @Test
    void an_unknown_tenant_sees_the_deployment_rows_alone_rather_than_everything() {
        assertThat(ids(report().visibleTo("nobody")))
                .as("a tenant with no rows of its own must not fall through to the whole report")
                .containsExactly("jenreg.auth.open");
    }

    @Test
    void the_report_still_carries_every_row_so_the_legs_above_are_not_vacuous() {
        // If the report held only deployment rows, every assertion above would pass for the wrong reason.
        assertThat(ids(report().advisories()))
                .contains("jenreg.auth.open", "jenreg.tls.acme", "jenreg.tls.globex");
        assertThat(report().scoped(Scope.TENANT)).as("and two of them really are tenant-scoped").hasSize(2);
    }

    /**
     * The advisors are discovered once, however many reports are asked for.
     *
     * <p>{@code PostureReport.discover} is called from request handlers - {@code GET /api/posture}, its admin twin
     * and the console's posture badge - and used to walk the module graph's service declarations and re-instantiate
     * every advisor on each one, before asking a single advisor anything. The answer still depends on the
     * configuration handed in, so only the discovery is held; this is what says so.
     *
     * <p>It counts constructions rather than timing anything: the claim is "once", not "fast", and a timing
     * assertion on a busy machine is the flake this repository keeps removing.
     */
    @Test
    void the_advisors_are_discovered_once_however_many_reports_are_asked_for() {
        PostureReport.discover(key -> null);
        int afterFirst = SampleSafetyAdvisor.built();
        assertThat(afterFirst)
                .as("discovery has to have happened at least once, or this proves nothing")
                .isPositive();

        for (int report = 0; report < 20; report++) {
            PostureReport.discover(key -> null);
        }
        assertThat(SampleSafetyAdvisor.built())
                .as("twenty further reports built no further advisors; one per report is the whole module graph "
                        + "walked per request, which is what the discovery rule forbids of a caller and what a "
                        + "holder here prevents for all of them")
                .isEqualTo(afterFirst);
    }
}
