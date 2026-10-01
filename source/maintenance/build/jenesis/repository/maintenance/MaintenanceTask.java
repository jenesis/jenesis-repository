package build.jenesis.repository.maintenance;

import module java.base;

/**
 * One recurring background pass over the deployment's repositories, supplied by a {@link MaintenanceTaskProvider}. The
 * server's scheduler owns the worker thread, the tenant and repository iteration, the single-writer lease and the
 * gauges; a task says only what to do per repository and optionally per tenant.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One instance serves the whole pass and the scheduler fans the
 *       {@code (tenant, repository)} units out over a bounded pool, so {@link #repository} is called concurrently on
 *       the same instance. {@link #tenant} runs after every repository unit of the pass has settled, and
 *       {@link #completed} after those.</li>
 *   <li><b>Idempotency / replay.</b> A pass re-runs on every interval and on demand, so it converges rather than
 *       accumulates, and back-fills from durable state when switched on late.</li>
 *   <li><b>Tenant scoping.</b> A unit reads and writes only through the scoped store of the {@link RepositoryContext}
 *       or {@link TenantContext} it is handed, and reads its settings per pass through
 *       {@link RepositoryContext#config() config()}, resolved for the unit's tenant. A dial the provider captured at
 *       construction ignores tenant overrides and later changes, so a provider reads only the enablement and the
 *       cadence.</li>
 *   <li><b>Error visibility.</b> A thrown failure is logged and counted on {@code jenrepo.maintenance.failures} and
 *       the pass continues with the next unit; a unit that swallows one hides it from that counter and from the
 *       task's status, even where a previous generation of a derived view keeps serving.
 *       <p>A unit sweeping many subjects may contain one subject's failure so the rest are swept, but raises the
 *       contained failures once before returning ({@link UnitFailures}). A unit that did not do its work never writes
 *       a freshness instant, marker flip or other "this view is current" claim, because downstream reads take that
 *       stamp as the statement that the data is current.</li>
 *   <li><b>Ordering / concurrency (exclusion).</b> {@link #exclusion()} names the single-writer mechanism, and the
 *       scheduler holds the lease {@code locks/<name>} for the whole pass exactly when it is {@link Exclusion#LEASE}.
 *       Declaring {@code LEASE} on a pass that rides a walk's segment claim serialises the fleet onto one node.</li>
 *   <li><b>Bounded work / cancellation.</b> A started unit runs to completion. On a lease lost mid-pass the scheduler
 *       submits no further units and skips {@link #completed}, so a pass that stopped early leaves its derived state
 *       readable and convergent. A task that enumerates pages rather than materialises.</li>
 * </ol>
 */
public interface MaintenanceTask {

    /** The task name, e.g. {@code cleanup}, {@code scan} - also the lease object an exclusive pass locks on. */
    String name();

    /** How often a full pass should run, read from the provider's own configuration at creation. */
    Duration interval();

    /** The moment this task is next due after {@code after}: one interval on by default, or the next moment a calendar
     *  schedule (a walk entry's cron expression) names. The scheduler asks it on arming and after each run. */
    default Instant next(Instant after) {
        return after.plus(interval());
    }

    /**
     * Which single-writer mechanism owns a pass. The scheduler asks once per pass and must get the same answer every
     * time, so a task whose owner depends on what is installed reads it from its construction state.
     */
    enum Exclusion {

        /** The scheduler holds the lease {@code locks/<name>} for the whole pass, so one node in the fleet runs it. The
         *  default, for a pass that mutates shared state with no exclusion of its own. */
        LEASE,

        /** The pass rides the shared walk, whose per-segment claim is its single-writer owner, so the scheduler takes
         *  no lease and the fleet parallelises on disjoint ranges. */
        WALK_CLAIM,

        /** No exclusion: a per-node pass whose result must exist on every replica (a gauge family, an idempotent
         *  upsert). Distinct from {@link #WALK_CLAIM}, which states a different reason for the same behaviour. */
        NONE;

        /** Whether the scheduler takes the task lease: {@code true} for {@link #LEASE} alone. */
        public boolean lease() {
            return this == LEASE;
        }
    }

    /** Which single-writer mechanism owns this pass; {@link Exclusion#LEASE} unless the task says otherwise. */
    default Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    /** Visit one repository. A thrown failure is logged and the pass continues with the next repository. */
    void repository(RepositoryContext context) throws IOException;

    /** After all of a tenant's repositories were visited - quota reconciliation and other tenant-wide work. */
    default void tenant(TenantContext context) throws IOException {
    }

    /** After the whole pass; the scheduler flushes the pass's gauges once this returns. */
    default void completed(Instant started) throws IOException {
    }
}
