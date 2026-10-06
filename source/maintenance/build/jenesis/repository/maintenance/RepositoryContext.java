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

    /**
     * This tenant beyond the repository the pass visits - its other repositories and its store - or {@link
     * TenantView#NONE} where the context reaches nothing past its own repository. Abstract, so every context says
     * which of the two it is: a context that inherited "nothing" would quietly write no tenant-wide row and find no
     * copy in a sibling repository.
     */
    TenantView tenantView();

    /**
     * The tenant of a {@link RepositoryContext}, as a pass that looks past the repository it visits sees it. A pass
     * leases the repository it visits and no other: what it writes into a sibling repository, or into the tenant's
     * store, is a row or a request one writer owns - an index row naming this repository's release, a mailbox post
     * asking the sibling's own pass to look again - never a change to what the sibling holds.
     */
    interface TenantView {

        /** A context reaching no repository but its own. */
        TenantView NONE = new TenantView() {
            @Override
            public Optional<RepositoryContext> repository(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<ArtifactStore> store() {
                return Optional.empty();
            }

            @Override
            public List<String> repositories() {
                return List.of();
            }
        };

        /** Another repository of this tenant - its store and its own effective configuration - or empty where there is
         *  none by that name. */
        Optional<RepositoryContext> repository(String name);

        /** The store scoped to this tenant's whole subspace, as {@link TenantContext#store} answers it - the dot-spaces
         *  a module keeps per tenant ({@code .vex}, {@code .closure}) beside the repositories. */
        Optional<ArtifactStore> store();

        /** The names of this tenant's repositories, each readable through {@link #repository}, as many as the
         *  scheduler's fan-out over the tenant visits. */
        List<String> repositories() throws IOException;
    }
}
