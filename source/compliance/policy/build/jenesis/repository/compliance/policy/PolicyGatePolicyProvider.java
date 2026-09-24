package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;

/**
 * Discovers the policy-as-code dimension of the gate: the rules come from {@code policy-rules}, one rule per line (or
 * separated by {@code ;}), each {@code <verdict> <expression>} - e.g.
 * {@code quarantine #severityRank >= 3 and #reachable} / {@code reject #malicious}. A rule whose verdict is unknown or
 * whose expression does not compile throws, so a live settings rebuild rejects a bad policy and rolls back (the
 * {@code create} contract). Empty when no rule is configured, so the gate carries the dimension only while there is a
 * policy to enforce - a deployment without this module, or with the setting blank, never gates on a code policy. The
 * rules are enforced identically on the publish and proxy paths (they read only the subject and its advisories, present
 * on both), so {@code path} is not consulted.
 */
public final class PolicyGatePolicyProvider implements GatePolicyProvider {

    @Override
    public String name() {
        return "policy";
    }

    @Override
    public Optional<GatePolicy> create(UnaryOperator<String> config, Path path) {
        return GateDimension.of(this, config, path).flatMap(dimension -> {
            // Split on a newline or ';', skip blanks, parse each line as '<verdict> <expression>'; a malformed rule
            // throws naming policy-rules, exactly as a bad version floor or severity threshold does, so the writer's
            // rollback covers a code policy like every other dial.
            List<PolicyRule> rules = dimension.lines("policy-rules", PolicyRule::parse);
            // No rule configured: the gate carries the dimension only while there is a policy to enforce.
            return dimension.enforcing(rules, () -> new PolicyGatePolicy(rules));
        });
    }
}
