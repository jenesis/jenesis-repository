package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;

/**
 * A gate dimension a test writes as a function: what it finds about a subject, under the rule it names. A dimension
 * always names its rule, so a test's probe does too.
 */
public final class Dimensions {

    private Dimensions() {
    }

    /** What a probe dimension finds about one subject, given the advisories the gate looked up for it. */
    @FunctionalInterface
    public interface Assessing {

        List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject, List<AdvisorySource.Advisory> advisories);
    }

    /** A dimension that finds what {@code assessing} finds, under {@code rule}. */
    public static GatePolicy ruled(String rule, Assessing assessing) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(assessing, "assessing");
        return new GatePolicy() {
            @Override
            public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                                       List<AdvisorySource.Advisory> advisories) {
                return assessing.assess(subject, advisories);
            }

            @Override
            public String rule() {
                return rule;
            }
        };
    }
}
