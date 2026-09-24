/**
 * The policy-as-code dimension of the compliance gate as a plugin module: it provides
 * {@link build.jenesis.repository.compliance.GatePolicyProvider} answering to {@code policy}, so the gate discovers an
 * expression-based policy through {@code ServiceLoader} and a deployment without this module simply never gates on a
 * code policy and lists no policy settings. It generalises the fixed gate dimensions - a CVSS threshold, a version
 * floor, a licence rule are each expressible as {@code <verdict> <expression>} over a subject's severity, licence,
 * reachability and coordinate metadata ({@code reject #severityRank >= 4 and #reachable}), folded into the same
 * strongest-verdict aggregation as the built-in dimensions. The expression engine is SpEL (a maintained expression
 * evaluator, so no policy grammar is hand-rolled), evaluated in a read-only sandbox that forbids type references,
 * constructors and method invocation, so an operator-authored rule reads facts and combines them but can never reach a
 * class or execute code - the reason a rule is bound to plain-string variables rather than reflected into any type here.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.policy {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires spring.expression;
    requires org.slf4j;
    exports build.jenesis.repository.compliance.policy to
            build.jenesis.repository.compliance.policy.test;
    provides build.jenesis.repository.compliance.GatePolicyProvider
            with build.jenesis.repository.compliance.policy.PolicyGatePolicyProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.policy.PolicySettingsContributor;
}
