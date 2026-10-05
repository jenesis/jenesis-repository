package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.Vex;
import build.jenesis.repository.compliance.VexStatement;
import build.jenesis.repository.compliance.VexStatus;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.compliance.Waiver;
import build.jenesis.repository.compliance.Waivers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A malicious-package flag is cleared by neither a VEX statement nor a waiver: a package that is harmful whatever it is
 * used for is held however the tenant attests its use, and a false positive is released by a reviewer instead. A VEX
 * statement and a waiver both still clear an ordinary advisory against the same package.
 */
class MaliciousSuppressionTest {

    private static final ComplianceGate.Subject STEALER = new ComplianceGate.Subject(
            "npm", "event-stream-helper", "1.0.0", List.of());

    private static final String MALWARE = "MAL-2026-0001";
    private static final String CVE = "CVE-2026-0002";

    private static final AdvisorySource FEED = AdvisorySource.of(Map.of(STEALER.coordinate(), List.of(
            new AdvisorySource.Advisory(MALWARE, Severity.NONE, true),
            new AdvisorySource.Advisory(CVE, Severity.CRITICAL, false, "1.0.1", List.of(CVE)))));

    private static final AdvisorySource MALWARE_ONLY = AdvisorySource.of(Map.of(STEALER.coordinate(), List.of(
            new AdvisorySource.Advisory(MALWARE, Severity.NONE, true))));

    private static final Instant WHEN = Instant.parse("2026-07-13T00:00:00Z");

    private static ComplianceGate gate() {
        return gate(FEED);
    }

    private static ComplianceGate gate(AdvisorySource feed) {
        // Both actions named rather than inherited: the scenario is about which dimension a statement reaches, so the
        // verdict each one raises must be told apart.
        return new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), feed)
                .malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE));
    }

    private static VexStatement notAffected(String vulnerability) {
        return new VexStatement(vulnerability, List.of(), List.of("pkg:npm/event-stream-helper"),
                VexStatus.NOT_AFFECTED, "vulnerable_code_not_present", null, WHEN, "urn:acme:vex:1");
    }

    private static Waiver accepted(String vulnerability) {
        return new Waiver(vulnerability, List.of(), "npm", STEALER.coordinate(), "1.0.0", WHEN,
                WHEN.plus(Duration.ofDays(30)), "Accepted.");
    }

    @Test
    void a_vex_statement_and_a_waiver_leave_a_malicious_flag_holding() {
        ComplianceGate.Assessment assessment = gate()
                .vex(Vex.of(List.of(notAffected(MALWARE), notAffected(CVE))))
                .waivers(Waivers.of(List.of(accepted(MALWARE), accepted(CVE))))
                .assess(STEALER);

        assertThat(assessment.verdict()).as("the malicious flag holds whatever the statements say")
                .isEqualTo(Verdict.QUARANTINE);
        assertThat(assessment.findings()).anySatisfy(finding -> {
            assertThat(finding.verdict()).isEqualTo(Verdict.QUARANTINE);
            assertThat(finding.detail()).contains(MALWARE);
        });
        assertThat(assessment.findings()).as("the ordinary advisory is cleared by the statement")
                .noneSatisfy(finding -> {
                    assertThat(finding.verdict()).isEqualTo(Verdict.REJECT);
                    assertThat(finding.detail()).contains(CVE);
                });
    }

    @Test
    void a_waiver_alone_leaves_a_malicious_flag_holding() {
        assertThat(gate(MALWARE_ONLY).waivers(Waivers.of(List.of(accepted(MALWARE)))).assess(STEALER).verdict())
                .isEqualTo(Verdict.QUARANTINE);
    }

    @Test
    void a_vex_statement_alone_leaves_a_malicious_flag_holding() {
        assertThat(gate(MALWARE_ONLY).vex(Vex.of(List.of(notAffected(MALWARE)))).assess(STEALER).verdict())
                .isEqualTo(Verdict.QUARANTINE);
    }
}
