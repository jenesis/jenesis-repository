package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;

/**
 * The policy-as-code dimension of the gate: it flattens a subject and the gate's shared advisory lookup into
 * {@link PolicyInput} variables and evaluates each configured {@link PolicyRule}, raising the rule's verdict for every
 * one that holds. It generalises the fixed dimensions - a CVSS threshold, a version floor, a licence policy are each a
 * rule an operator can now express directly ({@code reject #severityRank >= 4}, {@code quarantine
 * !#licenses.?[#this matches '(?i).*agpl.*'].empty}) - and folds into the same strongest-verdict aggregation, so it
 * composes with the built-in dimensions without either knowing of the other. Empty findings when no rule holds, so a
 * subject no rule speaks to passes this dimension exactly as before.
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
