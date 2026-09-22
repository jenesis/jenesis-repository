package build.jenesis.repository.compliance;

import module java.base;

/**
 * One discovered dimension of the {@link ComplianceGate}: it assesses a subject (and the advisories the gate
 * already looked up once for all dimensions) and reports the findings that warrant more than an allow. Which
 * dimensions run is decided by the modules on the deployment's module path - a license policy, a known-exploited
 * check - through {@link GatePolicyProvider}; the gate folds every finding into the strongest verdict, order
 * independently, so policies compose without knowing of each other. A deployment without a policy's module simply
 * never gates on that dimension.
 */
public interface GatePolicy {

    /** The findings this dimension raises for one subject; empty when it has nothing against it. {@code advisories}
     *  is the gate's single per-subject feed lookup, shared across dimensions so none repeats it. */
    List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject, List<AdvisorySource.Advisory> advisories);
}
