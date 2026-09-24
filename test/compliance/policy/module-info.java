/**
 * Tests of the policy-as-code gate dimension in isolation, no store and no framework: a rule expressed over a subject's
 * severity, licence, reachability and metadata raises its verdict when it holds and nothing when it does not, the
 * verdicts fold into the gate exactly as the built-in dimensions do, a malformed rule is rejected at create (so a live
 * rebuild rolls back), and a rule that reaches for a Java type or a method call evaluates to a non-match rather than
 * executing - the sandbox that makes operator-authored rules safe. The provider is driven through its public seam, so
 * the whole expression pipeline (parse, sandboxed evaluate, verdict) is pinned down before it is discovered onto the
 * gate.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.policy
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.policy.test {
    requires build.jenesis.repository.compliance.policy;
    requires build.jenesis.repository.compliance;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
