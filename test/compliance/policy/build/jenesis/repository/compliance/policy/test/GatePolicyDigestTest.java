package build.jenesis.repository.compliance.policy.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gate's policy digest is what a stored verdict is compared with before it is reused, so it must move with every
 * setting the gate is built from - its core dials and the settings its discovered dimensions read, here the
 * policy-as-code rules - and with the flavour, and stay put when nothing changed. Driven over the real dimension
 * resolution with the policy dimension installed.
 */
class GatePolicyDigestTest {

    private static ComplianceGate gate(Map<String, String> settings, GatePolicyProvider.Path path,
                                       List<String> denied, Severity threshold) {
        return new ComplianceGate(new VulnerabilityPolicy(threshold, Verdict.REJECT), AdvisorySource.none())
                .malicious(new MaliciousPackagePolicy().action(Verdict.REJECT))
                .denyList(new DenyListPolicy(denied).action(Verdict.REJECT))
                .policies(GatePolicyProvider.resolution(settings::get, path));
    }

    private static ComplianceGate gate(Map<String, String> settings) {
        return gate(settings, GatePolicyProvider.Path.PROXY, List.of(), Severity.CRITICAL);
    }

    @Test
    void the_same_settings_give_the_same_digest() {
        Map<String, String> settings = Map.of("policy-rules", "reject #malicious");
        assertThat(gate(settings).policy()).isEqualTo(gate(new HashMap<>(settings)).policy())
                .startsWith("sha256:");
    }

    @Test
    void a_setting_a_dimension_reads_moves_the_digest() {
        assertThat(gate(Map.of("policy-rules", "reject #malicious")).policy())
                .as("a rule changed").isNotEqualTo(gate(Map.of("policy-rules", "quarantine #malicious")).policy())
                .as("a rule added where there was none").isNotEqualTo(gate(Map.of()).policy());
        assertThat(gate(Map.of("policy-rules", "reject #malicious", "policy", "false")).policy())
                .as("a dimension switched off").isNotEqualTo(gate(Map.of("policy-rules", "reject #malicious")).policy());
    }

    @Test
    void the_core_dials_and_the_flavour_move_the_digest() {
        String base = gate(Map.of(), GatePolicyProvider.Path.PROXY, List.of(), Severity.CRITICAL).policy();
        assertThat(gate(Map.of(), GatePolicyProvider.Path.PROXY, List.of("org.evil:*"), Severity.CRITICAL).policy())
                .as("a deny-list entry").isNotEqualTo(base);
        assertThat(gate(Map.of(), GatePolicyProvider.Path.PROXY, List.of(), Severity.HIGH).policy())
                .as("a lowered threshold").isNotEqualTo(base);
        assertThat(gate(Map.of(), GatePolicyProvider.Path.PUBLISH, List.of(), Severity.CRITICAL).policy())
                .as("the other flavour's gate, so one flavour's verdict cannot stand for the other's")
                .isNotEqualTo(base);
    }

    @Test
    void the_resolution_names_every_setting_it_read_and_no_credential() {
        GatePolicyProvider.Resolution resolution = GatePolicyProvider.resolution(
                Map.of("policy-rules", "reject #malicious")::get, GatePolicyProvider.Path.PROXY);
        assertThat(resolution.read()).containsEntry("policy-rules", "reject #malicious")
                .containsEntry("policy", GatePolicyProvider.Resolution.UNSET);
        assertThat(new GatePolicyProvider.Resolution(GatePolicyProvider.Path.PROXY, List.of(),
                Map.of("snyk-token", "s3cr3t", "vendor-api-key", "")).describe())
                .as("a credential is carried as whether it is set").contains("snyk-token=<set>")
                .contains("vendor-api-key=<unset>").doesNotContain("s3cr3t");
    }
}
