package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence an inspector reads out of an artifact's bytes - here a secret, as a signature or an attestation would be -
 * rides a content-scan subject beside the package subject its format produced, often under the package's own
 * coordinate. The package subject carries the advisories and the deny-list, so the evidence is not asked of them
 * again: a quarantine listed every advisory twice, once with the package's place on the build graph and once without.
 */
class ContentScanSubjectTest {

    private static final AdvisorySource FEED = AdvisorySource.of(Map.of("org.apache.logging.log4j:log4j-core",
            List.of(new AdvisorySource.Advisory("CVE-2021-44228", Severity.CRITICAL, false, "2.15.0",
                    List.of("CVE-2021-44228")))));

    private static final String COORDINATE = "org.apache.logging.log4j:log4j-core";

    private static final ComplianceGate.Subject PACKAGE = new ComplianceGate.Subject("Maven", COORDINATE, "2.14.1",
            List.of(), ComplianceGate.Reachability.root(COORDINATE + ":2.14.1"));

    private static final ComplianceGate.Subject EVIDENCE = new ComplianceGate.Subject("Maven", COORDINATE, "2.14.1",
            List.of()).withSecrets(List.of(new ComplianceGate.DetectedSecret("aws-key", "an AWS access key",
            "AKIA****", "config.properties")));

    @Test
    void an_advisory_is_reported_once_with_the_package_place_on_the_build_graph() {
        ComplianceGate.Assessment assessment = new ComplianceGate(
                new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
                FEED)
                .assess(List.of(PACKAGE, EVIDENCE));

        assertThat(assessment.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(assessment.findings()).filteredOn(finding -> finding.detail().contains("CVE-2021-44228"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("reachable on the build graph"));
    }

    @Test
    void a_deny_list_entry_is_reported_once() {
        ComplianceGate.Assessment assessment = new ComplianceGate(
                new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
                AdvisorySource.NONE).denyList(new DenyListPolicy(List.of("org.apache.logging.log4j:*")))
                .assess(List.of(PACKAGE, EVIDENCE));

        assertThat(assessment.findings()).filteredOn(finding -> finding.detail().startsWith("Deny-listed"))
                .hasSize(1);
    }
}
