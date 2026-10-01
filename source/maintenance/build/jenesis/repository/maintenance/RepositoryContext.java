package build.jenesis.repository.maintenance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What a {@link MaintenanceTask} sees while visiting one repository: its un-metered scoped store (the scheduler
 * reconciles usage in the tenant hook), the effective configuration lookup, the pass timestamp, and a gauge sink whose
 * rows the scheduler publishes wholesale after the pass.
 */
public interface RepositoryContext {

    String tenant();

    String repository();

    /** The store scoped to this tenant and repository, un-metered. */
    ArtifactStore store();

    /** The effective configuration lookup, {@code null} for an unset key. */
    UnaryOperator<String> config();

    /**
     * The failure accumulator for this unit's work, which the scheduler raises once the unit returns, so recording a
     * failure is enough to report it.
     *
     * @param work        what this unit was doing, so the raised failure names the pass and repository
     * @param consequence what the failure means for the deployment: which derived state is missing, what was not
     *                    stamped, and when it converges
     */
    UnitFailures failures(String work, String consequence);

    /** The pass timestamp, stable for the whole pass. */
    Instant now();

    /** Report one per-repository gauge row; a task's rows replace its previous pass's rows wholesale. */
    void gauge(String name, String description, Map<String, String> tags, double value);

    /**
     * Increments a monotonic per-repository counter for a state transition, where a {@link #gauge} row is a snapshot.
     * Best-effort: a lost increment only under-counts, and a context with no metrics sink drops it.
     */
    default void counter(String name, String description, Map<String, String> tags, double amount) {
    }
}
