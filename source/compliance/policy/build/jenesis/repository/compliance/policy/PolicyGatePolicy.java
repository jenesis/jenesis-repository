package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;

/**
 * The policy-as-code dimension: it flattens a subject and the gate's advisory lookup into {@link PolicyInput} variables
 * and evaluates each configured {@link PolicyRule}, raising the verdict of every rule that holds. A CVSS threshold, a
 * version floor or a licence policy is each expressible as a rule ({@code reject #severityRank >= 4},
 * {@code quarantine !#licenses.?[#this matches '(?i).*agpl.*'].empty}), folded into the same strongest-verdict
 * aggregation as the built-in dimensions. No rule holding means no findings.
 */
final class PolicyGatePolicy implements GatePolicy {

    private final List<PolicyRule> rules;

    PolicyGatePolicy(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                               List<AdvisorySource.Advisory> advisories) {
        Map<String, Object> variables = PolicyInput.variables(subject, advisories);
        List<ComplianceGate.Finding> findings = new ArrayList<>();
        for (PolicyRule rule : rules) {
            if (rule.expression().matches(variables)) {
                findings.add(new ComplianceGate.Finding(rule.verdict(),
                        "Policy rule matched (" + rule.verdict().name().toLowerCase(Locale.ROOT) + "): "
                                + rule.expression().text()));
            }
        }
        return findings;
    }
}
