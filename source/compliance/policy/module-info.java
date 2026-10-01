/**
 * The policy-as-code dimension of the compliance gate: a {@link build.jenesis.repository.compliance.GatePolicyProvider}
 * answering to {@code policy}; without this module no code policy gates and no policy setting is listed. A CVSS
 * threshold, version floor or licence rule is each expressible as {@code <verdict> <expression>} over a subject's
 * severity, licence, reachability and metadata ({@code reject #severityRank >= 4 and #reachable}), folded into the
 * strongest-verdict aggregation. The engine is SpEL in a read-only sandbox forbidding type references, constructors and
 * method invocation, with rules bound to plain variables, so a rule can never reach a class or execute code.
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
