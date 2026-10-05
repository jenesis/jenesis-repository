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
 * A subject the advisory databases know by another name - a Debian binary under the source package it was built from -
 * is asked of the feeds under that name, while everything that names the package an operator sees, the deny-list
 * among it, keeps to the subject's own coordinate.
 */
class AdvisedSubjectTest {

    private static final AdvisorySource FEED = AdvisorySource.of(Map.of("openssl",
            List.of(new AdvisorySource.Advisory("DSA-5000-1", Severity.CRITICAL, false, null,
                    List.of("CVE-2024-0727")))));

    private static final ComplianceGate.Subject BINARY = new ComplianceGate.Subject("Debian", "libssl3",
            "3.0.15-1+b1", List.of());

    private static final AdvisorySource.Query SOURCE = new AdvisorySource.Query("Debian", "openssl", "3.0.15-1");

    @Test
    void a_binary_is_asked_about_under_its_source_package() {
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), FEED);

        assertThat(gate.assess(List.of(BINARY.withAdvised(SOURCE))).verdict()).isEqualTo(Verdict.REJECT);
        assertThat(gate.assess(List.of(BINARY)).verdict())
                .as("asked under its own name, the databases know nothing of it").isEqualTo(Verdict.ALLOW);
    }

    @Test
    void the_name_asked_about_survives_every_restamp() {
        ComplianceGate.Subject restamped = BINARY.withAdvised(SOURCE).withSecrets(List.of())
                .withAttestation(null).withSignatures(List.of()).withMaintainers(List.of())
                .withDependencies(List.of()).withAbout(null);

        assertThat(restamped.asked()).isEqualTo(SOURCE);
        assertThat(BINARY.asked()).isEqualTo(new AdvisorySource.Query("Debian", "libssl3", "3.0.15-1+b1"));
    }

    @Test
    void the_deny_list_names_the_package_an_operator_sees() {
        ComplianceGate.Assessment assessment = new ComplianceGate(
                new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), AdvisorySource.NONE)
                .denyList(new DenyListPolicy(List.of("libssl3")))
                .assess(List.of(BINARY.withAdvised(SOURCE)));

        assertThat(assessment.verdict()).isEqualTo(Verdict.REJECT);
    }
}
