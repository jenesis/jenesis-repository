package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * One discovered dimension of the {@link ComplianceGate}: it assesses a subject (and the advisories the gate
 * already looked up once for all dimensions) and reports the findings that warrant more than an allow. Which
 * dimensions run is decided by the modules on the deployment's module path - a license policy, a known-exploited
 * check - through {@link GatePolicyProvider}; the gate folds every finding into the strongest verdict, order
 * independently, so policies compose without knowing of each other. A deployment without a policy's module simply
 * never gates on that dimension.
 *
 * <p>Two hooks let a dimension answer from evidence about the stored artifact rather than from the subject alone,
 * and both default to doing nothing. {@link #bound} hands the dimension the repository and the artifact as it was
 * stored, so it can read what that repository has durably recorded about those bytes - a content scan's report,
 * collected off the publish path. {@link #advisories} hands the gate what such evidence says is vulnerable, before
 * any dimension runs, so the advisories meet the vulnerability threshold, the malicious flag, VEX statements, waivers
 * and every other dimension exactly as a feed's advisories do.
 */
public interface GatePolicy {

    /** The findings this dimension raises for one subject; empty when it has nothing against it. {@code advisories}
     *  is the gate's single per-subject feed lookup, shared across dimensions so none repeats it, with what every
     *  dimension's {@link #advisories} added. */
    List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject, List<AdvisorySource.Advisory> advisories);

    /**
     * What this dimension holds or refuses an artifact for, in an operator's words - "Licence", "Known-exploited
     * vulnerability" - which the review queue shows beside every version it held. The gate stamps it on each finding
     * this dimension raises. No default, since no one wording is right for every dimension.
     */
    String rule();

    /**
     * The advisories this dimension holds about {@code subject} beyond what the feeds report - read by the gate before
     * any dimension assesses, and added to the feeds' answer. Empty by default.
     *
     * <p>Asked only of a packaged subject that names a version, the same subjects a feed is asked about, and it
     * renders: it reads what the dimension already holds or what {@link #bound} gave it access to, and never fetches.
     */
    default List<AdvisorySource.Advisory> advisories(ComplianceGate.Subject subject) {
        return List.of();
    }

    /**
     * This dimension bound to one stored artifact: {@code repository} is the repository's own scoped store and
     * {@code artifact} the artifact as it was stored there, its {@link ArtifactDescriptor#hash() hash} naming the
     * blob. The gate binds every dimension before it assesses a publish, and again when it re-assesses a held artifact,
     * so a dimension whose answer depends on what the repository recorded about these bytes reads it here. The bound
     * dimension may read the store - a point read, never an enumeration, since a publish waits on it - and must not
     * write it. This dimension itself by default, which is what a dimension reading only the subject wants.
     */
    default GatePolicy bound(ArtifactStore repository, ArtifactDescriptor artifact) {
        return this;
    }
}
