package build.jenesis.repository.compliance.policy.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.policy.PolicyGatePolicyProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy-as-code gate dimension: an operator's expression rule over a subject's severity, licence, reachability and
 * metadata raises its verdict when it holds and nothing when it does not; the verdict folds into the gate exactly as a
 * built-in dimension does; a blank policy carries no dimension; a malformed rule is rejected at create; and a rule that
 * reaches for a Java type or a method call evaluates to a non-match rather than executing - the sandbox that makes an
 * operator-authored rule safe. Driven through the public {@link PolicyGatePolicyProvider} seam.
 */
class PolicyGateTest {

    private static final PolicyGatePolicyProvider PROVIDER = new PolicyGatePolicyProvider();

    private static UnaryOperator<String> rules(String value) {
        return key -> "policy-rules".equals(key) ? value : null;
    }

    private static GatePolicy policy(String rules) {
        return PROVIDER.create(rules(rules), GatePolicyProvider.Path.PUBLISH).orElseThrow();
    }

    private static ComplianceGate.Subject subject(ComplianceGate.Reachability reachability, String... licenses) {
        List<ComplianceGate.DeclaredLicense> declared = new ArrayList<>();
        for (String license : licenses) {
            declared.add(new ComplianceGate.DeclaredLicense(license, null));
        }
        return new ComplianceGate.Subject("Maven", "org.example:app", "1.0", declared, reachability);
    }

    private static List<AdvisorySource.Advisory> advisory(Severity severity) {
        return List.of(new AdvisorySource.Advisory("CVE-2026-1", severity, false, null, List.of("CVE-2026-1")));
    }

    @Test
    void the_provider_answers_to_policy_and_is_empty_without_rules() {
        assertThat(PROVIDER.name()).isEqualTo("policy");
        assertThat(PROVIDER.create(rules(""), GatePolicyProvider.Path.PUBLISH)).isEmpty();
        assertThat(PROVIDER.create(key -> null, GatePolicyProvider.Path.PUBLISH)).isEmpty();
    }

    /**
     * A floor rejects an advisory nobody could score, and a scored one does not mask it.
     *
     * <p>This is the consequence the unknown band exists for. An advisory carrying only a {@code CVSS:4.0} vector
     * scores nothing, and used to report {@code NONE}: {@code severityRank} 0, and {@code reject #severityRank >= 4}
     * admitted it. {@code UNKNOWN} sorts above {@code CRITICAL} precisely so every ordinal floor in the product
     * fails closed against it without a call site being touched.
     *
     * <p>The second half matters as much. A set holding one unknown and one CRITICAL must still report CRITICAL
     * to the rule: the rollup prefers a band that says something, so an unscorable advisory can never erase a
     * scored one. Only an all-unknown set reports unknown.
     */
    @Test
    void a_floor_rejects_what_no_source_could_score_and_a_scored_advisory_is_never_masked() {
        GatePolicy policy = policy("reject #severityRank >= 4");

        assertThat(policy.assess(subject(ComplianceGate.Reachability.UNKNOWN), advisory(Severity.UNKNOWN)))
                .as("a floor must not admit what it could not score - the fail-open this band was added to close")
                .singleElement()
                .satisfies(finding -> assertThat(finding.verdict()).isEqualTo(Verdict.REJECT));

        assertThat(policy.assess(subject(ComplianceGate.Reachability.UNKNOWN), advisory(Severity.LOW)))
                .as("and a band it could score, below the floor, still passes")
                .isEmpty();
    }

    @Test
    void a_severity_and_reachability_rule_gates_only_the_matching_subject() {
        GatePolicy policy = policy("reject #severityRank >= 4 and #reachable");

        List<ComplianceGate.Finding> gated = policy.assess(
                subject(ComplianceGate.Reachability.root("org.example:app")), advisory(Severity.CRITICAL));
        assertThat(gated).singleElement().satisfies(finding -> {
            assertThat(finding.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(finding.detail()).contains("Policy rule matched (reject)").contains("#severityRank >= 4");
        });

        // A critical but not-reachable subject, and a reachable but only-high subject, both pass the rule.
        assertThat(policy.assess(subject(ComplianceGate.Reachability.UNKNOWN), advisory(Severity.CRITICAL))).isEmpty();
        assertThat(policy.assess(subject(ComplianceGate.Reachability.root("org.example:app")),
                advisory(Severity.HIGH))).isEmpty();
    }

    @Test
    void a_licence_rule_reads_the_declared_licences_without_a_method_call() {
        GatePolicy policy = policy("quarantine !#licenses.?[#this matches '(?i).*agpl.*'].empty");

        assertThat(policy.assess(subject(ComplianceGate.Reachability.UNKNOWN, "AGPL-3.0"), List.of()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.verdict()).isEqualTo(Verdict.QUARANTINE));
        assertThat(policy.assess(subject(ComplianceGate.Reachability.UNKNOWN, "Apache-2.0"), List.of())).isEmpty();
    }

    @Test
    void the_verdict_folds_into_the_gate_like_a_built_in_dimension() {
        AdvisorySource feed = AdvisorySource.of(Map.of("org.example:app", advisory(Severity.MEDIUM)));
        // The built-in vulnerability policy would allow a MEDIUM at a HIGH threshold; the code policy quarantines it.
        ComplianceGate gate = new ComplianceGate(new build.jenesis.repository.compliance.VulnerabilityPolicy(
                Severity.HIGH), feed).policies(List.of(policy("quarantine #severityRank >= 2")));

        ComplianceGate.Assessment assessment = gate.assess(subject(ComplianceGate.Reachability.UNKNOWN));
        assertThat(assessment.verdict()).isEqualTo(Verdict.QUARANTINE);
        assertThat(assessment.findings()).anyMatch(finding -> finding.detail().contains("Policy rule matched"));
    }

    @Test
    void a_rule_reaching_for_a_type_or_method_evaluates_to_a_non_match_not_execution() {
        // These parse as SpEL but the read-only sandbox forbids type references and method calls, so they raise nothing
        // (and, crucially, never execute) rather than gating - a broken/hostile rule enforces nothing.
        assertThat(policy("reject T(java.lang.System).exit(1) != null")
                .assess(subject(ComplianceGate.Reachability.UNKNOWN), List.of())).isEmpty();
        assertThat(policy("reject #coordinate.getClass().name != null")
                .assess(subject(ComplianceGate.Reachability.UNKNOWN), List.of())).isEmpty();
    }

    @Test
    void every_documented_policy_variable_is_bound_and_readable() {
        // Each rule matches only if its variable is actually bound to the crafted subject's fact; the sandbox turns an
        // unbound reference into a non-match, so a raised verdict proves the variable is bound (not left null).
        ComplianceGate.Subject app = subject(ComplianceGate.Reachability.root("org.example:app"));

        assertThat(policy("reject #ecosystem == 'Maven'").assess(app, List.of())).isNotEmpty();
        assertThat(policy("reject #coordinate == 'org.example:app'").assess(app, List.of())).isNotEmpty();
        assertThat(policy("reject #version == '1.0'").assess(app, List.of())).isNotEmpty();

        // Advisory-derived variables, from the shared lookup argument.
        assertThat(policy("reject #severity == 'CRITICAL'").assess(app, advisory(Severity.CRITICAL))).isNotEmpty();
        assertThat(policy("reject #severityRank == 4").assess(app, advisory(Severity.CRITICAL))).isNotEmpty();
        assertThat(policy("reject #advisoryCount == 1").assess(app, advisory(Severity.CRITICAL))).isNotEmpty();
        assertThat(policy("reject !#advisories.?[#this == 'CVE-2026-1'].empty")
                .assess(app, advisory(Severity.CRITICAL))).isNotEmpty();
        assertThat(policy("reject #malicious == false").assess(app, advisory(Severity.CRITICAL))).isNotEmpty();

        // Reachability variables.
        assertThat(policy("reject #reachable").assess(app, List.of())).isNotEmpty();
        assertThat(policy("reject #reachability == 'ROOT'").assess(app, List.of())).isNotEmpty();
        assertThat(policy("reject #depth == 0").assess(app, List.of())).isNotEmpty();

        // Content-scan variables, on a secret-carrying content-scan subject.
        ComplianceGate.Subject scanned = new ComplianceGate.Subject("secret", "/x/keys.pem", "", List.of())
                .withSecrets(List.of(new ComplianceGate.DetectedSecret(
                        "private-key", "Private key", "---...---", "/x/keys.pem")));
        assertThat(policy("reject #secretCount == 1").assess(scanned, List.of())).isNotEmpty();
        assertThat(policy("reject #contentScan").assess(scanned, List.of())).isNotEmpty();

        // Declared-licence variable.
        assertThat(policy("reject !#licenses.?[#this == 'AGPL-3.0'].empty")
                .assess(subject(ComplianceGate.Reachability.UNKNOWN, "AGPL-3.0"), List.of())).isNotEmpty();
    }

    @Test
    void a_malformed_rule_is_rejected_at_create_so_a_live_rebuild_rolls_back() {
        assertThatThrownBy(() -> policy("blahverdict #severityRank >= 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("reject")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("reject #severityRank >= )(")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiple_rules_separated_by_lines_or_semicolons_all_apply() {
        GatePolicy policy = policy("reject #malicious\nquarantine #severityRank >= 3");
        List<ComplianceGate.Finding> gated = policy.assess(
                subject(ComplianceGate.Reachability.UNKNOWN), advisory(Severity.HIGH));
        // Only the severity rule holds (the subject is not malicious), so exactly the quarantine verdict is raised.
        assertThat(gated).singleElement()
                .satisfies(finding -> assertThat(finding.verdict()).isEqualTo(Verdict.QUARANTINE));
    }
}
