package build.jenesis.repository.compliance.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.compliance.Waiver;
import build.jenesis.repository.compliance.Waivers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accept-risk waiver suppression the compliance gate applies: an advisory an operator has recorded a still-standing
 * waiver for on the subject is kept out of every dimension and recorded as an informational allow naming the waiver and
 * its expiry, so an explicitly accepted risk is downgraded rather than rejecting the upload - while a waiver for another
 * coordinate or another vulnerability leaves the advisory to bite. No store and no framework: {@link Waivers#of} is
 * exercised directly over fixed waivers (the ledger's active-waiver filtering is the findings module's concern).
 */
class WaiverSuppressionTest {

    private static final ComplianceGate.Subject LOG4J = new ComplianceGate.Subject(
            "Maven", "org.apache.logging.log4j:log4j-core", "2.14.1", List.of());

    private static final AdvisorySource FEED = AdvisorySource.of(Map.of(
            "org.apache.logging.log4j:log4j-core",
            List.of(new AdvisorySource.Advisory("CVE-2021-44228", Severity.CRITICAL, false, "2.15.0",
                    List.of("CVE-2021-44228")))));

    private static final Instant GRANTED = Instant.parse("2026-07-13T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-08-13T00:00:00Z");

    private static Waiver waiver(String vulnerability, String coordinate) {
        return new Waiver(vulnerability, List.of(), "Maven", coordinate, "2.14.1", GRANTED, EXPIRES,
                "Accepted pending the 2.17 bump.");
    }

    private static Verdict verdict(Waivers waivers) {
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), FEED).waivers(waivers).assess(LOG4J)
                .verdict();
    }

    @Test
    void a_critical_advisory_rejects_without_a_waiver() {
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), FEED).assess(LOG4J).verdict())
                .isEqualTo(Verdict.REJECT);
    }

    @Test
    void an_active_waiver_downgrades_the_advisory_and_records_it() {
        ComplianceGate.Assessment assessment =
                new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), FEED)
                        .waivers(Waivers.of(List.of(waiver("CVE-2021-44228", LOG4J.coordinate())))).assess(LOG4J);

        assertThat(assessment.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(assessment.findings()).hasSize(1);
        ComplianceGate.Finding finding = assessment.findings().getFirst();
        assertThat(finding.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(finding.detail())
                .contains("CVE-2021-44228")
                .contains("risk accepted per waiver")
                .contains("until " + EXPIRES)
                .contains("Accepted pending the 2.17 bump.");
    }

    @Test
    void a_waiver_matching_a_ghsa_alias_suppresses_a_cve_named_advisory() {
        AdvisorySource ghsaFeed = AdvisorySource.of(Map.of(
                "org.apache.logging.log4j:log4j-core",
                List.of(new AdvisorySource.Advisory("GHSA-jfh8-c2jp-5v3q", Severity.CRITICAL, false, "2.15.0",
                        List.of("CVE-2021-44228")))));

        Waiver byCve = new Waiver("CVE-2021-44228", List.of(), "Maven", LOG4J.coordinate(), "2.14.1", GRANTED,
                EXPIRES, null);
        assertThat(new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH), ghsaFeed)
                .waivers(Waivers.of(List.of(byCve))).assess(LOG4J).verdict()).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void a_waiver_for_another_coordinate_does_not_suppress() {
        assertThat(verdict(Waivers.of(List.of(waiver("CVE-2021-44228", "com.example:other"))))).isEqualTo(Verdict.REJECT);
    }

    @Test
    void a_waiver_for_another_vulnerability_does_not_suppress() {
        assertThat(verdict(Waivers.of(List.of(waiver("CVE-2020-0001", LOG4J.coordinate()))))).isEqualTo(Verdict.REJECT);
    }

    @Test
    void no_waiver_leaves_the_advisory_to_bite() {
        assertThat(verdict(Waivers.NONE)).isEqualTo(Verdict.REJECT);
    }
}
