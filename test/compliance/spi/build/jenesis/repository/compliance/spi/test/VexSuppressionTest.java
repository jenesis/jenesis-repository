package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.Vex;
import build.jenesis.repository.compliance.VexProvider;
import build.jenesis.repository.compliance.VexStatement;
import build.jenesis.repository.compliance.VexStatus;
import build.jenesis.repository.compliance.VulnerabilityPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The VEX suppression the compliance gate applies: an advisory a tenant's VEX statement marks not-applicable to the
 * subject ({@code not_affected} / {@code fixed}) is kept out of every dimension and recorded as an informational allow
 * naming the statement, so an attested-not-applicable CVE is downgraded rather than quarantining or rejecting the
 * upload - while an unrelated statement, a mismatched product, or a newer re-opened claim leaves the advisory to bite.
 * No store and no framework: {@link Vex#of} is exercised directly over fixed statements.
 */
class VexSuppressionTest {

    private static final ComplianceGate.Subject LOG4J = new ComplianceGate.Subject(
            "Maven", "org.apache.logging.log4j:log4j-core", "2.14.1", List.of());

    private static final AdvisorySource FEED = AdvisorySource.of(Map.of(
            "org.apache.logging.log4j:log4j-core",
            List.of(new AdvisorySource.Advisory("CVE-2021-44228", Severity.CRITICAL, false, "2.15.0",
                    List.of("CVE-2021-44228")))));

    private static final String LOG4J_PURL = "pkg:maven/org.apache.logging.log4j/log4j-core";

    private static Verdict verdict(AdvisorySource feed, Vex vex) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), feed).vex(vex).assess(LOG4J).verdict();
    }

    private static Vex statement(VexStatus status, String justification, String product, String document,
                                 Instant when) {
        return Vex.of(List.of(new VexStatement("CVE-2021-44228", List.of(), List.of(product), status,
                justification, null, when, document)));
    }

    @Test
    void a_high_advisory_rejects_without_vex() {
        ComplianceGate.Assessment assessment =
                new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), FEED).assess(LOG4J);

        assertThat(assessment.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(assessment.findings()).anyMatch(finding -> finding.detail().contains("CVE-2021-44228"));
    }

    @Test
    void a_not_affected_statement_downgrades_the_advisory_and_records_it() {
        Vex vex = Vex.of(List.of(new VexStatement("CVE-2021-44228", List.of(), List.of(LOG4J_PURL),
                VexStatus.NOT_AFFECTED, "vulnerable_code_not_present", "The vulnerable JndiLookup class is stripped.",
                Instant.parse("2026-07-13T00:00:00Z"), "urn:acme:vex:1")));

        ComplianceGate.Assessment assessment =
                new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), FEED).vex(vex).assess(LOG4J);

        assertThat(assessment.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(assessment.findings()).hasSize(1);
        ComplianceGate.Finding finding = assessment.findings().getFirst();
        assertThat(finding.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(finding.detail())
                .contains("CVE-2021-44228")
                .contains("not applicable per VEX")
                .contains("not_affected")
                .contains("vulnerable_code_not_present")
                .contains("urn:acme:vex:1");
    }

    @Test
    void a_fixed_statement_also_suppresses() {
        assertThat(verdict(FEED, statement(VexStatus.FIXED, null, LOG4J_PURL, "urn:acme:vex:2",
                Instant.parse("2026-07-13T00:00:00Z")))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void an_affected_statement_does_not_suppress() {
        assertThat(verdict(FEED, statement(VexStatus.AFFECTED, null, LOG4J_PURL, "urn:acme:vex:3",
                Instant.parse("2026-07-13T00:00:00Z")))).isEqualTo(Verdict.REJECT);
    }

    @Test
    void a_newer_reopened_claim_wins_over_an_older_not_affected() {
        Vex vex = Vex.of(List.of(
                new VexStatement("CVE-2021-44228", List.of(), List.of(LOG4J_PURL), VexStatus.NOT_AFFECTED, null, null,
                        Instant.parse("2026-01-01T00:00:00Z"), "urn:acme:vex:old"),
                new VexStatement("CVE-2021-44228", List.of(), List.of(LOG4J_PURL), VexStatus.AFFECTED, null, null,
                        Instant.parse("2026-07-01T00:00:00Z"), "urn:acme:vex:new")));

        assertThat(verdict(FEED, vex)).isEqualTo(Verdict.REJECT);
    }

    @Test
    void a_statement_for_another_product_does_not_suppress() {
        assertThat(verdict(FEED, statement(VexStatus.NOT_AFFECTED, null, "pkg:maven/com.example/other",
                "urn:acme:vex:4", Instant.parse("2026-07-13T00:00:00Z")))).isEqualTo(Verdict.REJECT);
    }

    @Test
    void a_statement_naming_the_cve_matches_an_advisory_known_by_a_ghsa_alias() {
        AdvisorySource ghsaFeed = AdvisorySource.of(Map.of(
                "org.apache.logging.log4j:log4j-core",
                List.of(new AdvisorySource.Advisory("GHSA-jfh8-c2jp-5v3q", Severity.CRITICAL, false, "2.15.0",
                        List.of("CVE-2021-44228")))));

        assertThat(verdict(ghsaFeed, statement(VexStatus.NOT_AFFECTED, null, LOG4J_PURL, "urn:acme:vex:5",
                Instant.parse("2026-07-13T00:00:00Z")))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void a_version_pinned_product_only_matches_that_version() {
        // The statement pins 2.13.0; the subject is 2.14.1, so it does not apply and the advisory still bites.
        assertThat(verdict(FEED, statement(VexStatus.NOT_AFFECTED, null, LOG4J_PURL + "@2.13.0", "urn:acme:vex:6",
                Instant.parse("2026-07-13T00:00:00Z")))).isEqualTo(Verdict.REJECT);
    }

    @Test
    void with_no_vex_plugin_the_provider_resolves_to_the_none_fallback_and_the_gate_still_screens() {
        // No vex module is on this test's module path, so ServiceLoader discovers no VexProvider and resolve() yields
        // the fail-safe fallback: a provider that returns Vex.NONE for every tenant (its store argument unused). A
        // deployment without the vex plugin therefore still boots and screens - the high advisory rejects, nothing is
        // suppressed - rather than crashing on an absent capability.
        VexProvider provider = VexProvider.resolve();
        Vex vex = provider.over("acme", null, key -> null);

        assertThat(vex).isSameAs(Vex.NONE);
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), FEED).vex(vex).assess(LOG4J).verdict())
                .isEqualTo(Verdict.REJECT);
    }
}
