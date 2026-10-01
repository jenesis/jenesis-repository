package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;

/**
 * Discovers the policy-as-code dimension: rules from {@code policy-rules}, one per line or separated by {@code ;}, each
 * {@code <verdict> <expression>} ({@code quarantine #severityRank >= 3 and #reachable}, {@code reject #malicious}). An
 * unknown verdict or an uncompilable expression throws, so a bad policy rolls back. Empty when no rule is configured,
 * so the gate carries the dimension only while there is a policy. The rules read only the subject and its advisories,
 * present on both legs, so {@code path} is not consulted.
 */
public final class PolicyGatePolicyProvider implements GatePolicyProvider {

    @Override
    public String name() {
        return "policy";
    }

    @Override
    public Optional<GatePolicy> create(UnaryOperator<String> config, Path path) {
        return GateDimension.of(this, config, path).flatMap(dimension -> {
            // One '<verdict> <expression>' per line or ';', blanks skipped; a malformed rule throws naming
            // policy-rules, so the writer's rollback covers it as for every other dial.
            List<PolicyRule> rules = dimension.lines("policy-rules", PolicyRule::parse);
            // The gate carries the dimension only while a rule is configured.
            return dimension.enforcing(rules, () -> new PolicyGatePolicy(rules));
        });
    }
}
