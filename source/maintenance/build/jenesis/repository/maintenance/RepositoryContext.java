package build.jenesis.repository.maintenance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What a {@link MaintenanceTask} sees while visiting one repository: the repository's un-metered scoped store (a
 * sweep deletes outside the quota metering; the scheduler reconciles usage in the tenant hook), the effective
 * configuration lookup (runtime settings layered over the deployment properties), the pass timestamp (stable across
 * the pass, so ordering and ageing are consistent), and a gauge sink whose rows the scheduler publishes wholesale
 * after the pass.
 */
public interface RepositoryContext {

    String tenant();

    String repository();

    /** The store scoped to this tenant and repository, un-metered. */
    ArtifactStore store();

    /** The effective configuration lookup, {@code null} for an unset key. */
    UnaryOperator<String> config();

    /**
     * The failure accumulator for this unit's work, raised by the scheduler when the unit returns.
     *
     * <p>Contract clause 4 says a unit that could not do its work must throw, and clause 6 says it must not abandon
     * a million coordinates over one poisoned jar. {@link UnitFailures} satisfies both - contain each subject, name
     * what failed, raise once - and every pass that sweeps subjects wants it. It was documented as "the shared
     * idiom", which is to say a pass had to know it existed, construct one, and remember to raise it at the end;
     * a pass that forgot the last step swallowed everything and read as clean.
     *
     * <p>Asking the context for it moves that last step here: the scheduler raises what this collected once the
     * unit returns, so recording a failure is enough to report it. A pass that never records still reports nothing,
     * which no API can prevent - what this removes is the case where a pass did the work and lost it on the way out.
     *
     * @param work        what this unit was doing, so the raised failure names which pass over which repository
     *                    gave way rather than only that something did
     * @param consequence what a deployment should read into the failure - which derived state is missing, what was
     *                    deliberately not stamped, and when it converges
     */
    UnitFailures failures(String work, String consequence);

    /** The pass timestamp, stable for the whole pass. */
    Instant now();

    /** Report one per-repository gauge row; a task's rows replace its previous pass's rows wholesale. */
    void gauge(String name, String description, Map<String, String> tags, double value);

    /**
     * Increment a monotonic per-repository counter for a state <em>transition</em> - a forwarding delivery
     * outcome, a park - distinct from the wholesale-replaced {@link #gauge} rows (a counter accumulates, a gauge
     * is a snapshot). Best-effort: a lost increment only under-counts, and a context with no metrics sink (a test
     * fake, or a pass on a registry-less node) simply drops it - hence a no-op default rather than a required
     * method.
     */
    default void counter(String name, String description, Map<String, String> tags, double amount) {
    }
}
