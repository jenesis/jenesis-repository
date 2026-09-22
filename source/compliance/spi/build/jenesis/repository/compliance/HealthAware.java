package build.jenesis.repository.compliance;

/**
 * A {@link GatePolicy} dimension whose maintainer-health source can be rebound - the seam the {@link ComplianceGate}
 * uses to overlay a per-repository health source onto its discovered health dimension without naming it. The screen has
 * the request's scoped store (and so the durable health ledger); the boot-built policy does not, so the deployment
 * overlays the ledger onto the gate at screen time through {@link ComplianceGate#health}, which rebinds every
 * health-aware dimension to the supplied source. A dimension that scores off no health source simply never implements
 * this, and the gate leaves it untouched. Kept generic (the gate names no concrete dimension, §2): the maintainer-health
 * policy opts in by implementing this, exactly as a source opts into {@link HealthSource} by implementing it.
 */
public interface HealthAware {

    /** This dimension reading maintainer-health from {@code source} instead of the one it was built with - the
     *  ledger-backed source the gate overlays at screen time, so the dimension scores off the persisted answer rather
     *  than a live probe (Principle 10). Returns the rebound dimension; the receiver is unchanged (policies are
     *  immutable, like the gate). */
    GatePolicy withHealth(HealthSource source);
}
