package build.jenesis.repository.maintenance;

import module java.base;

/**
 * One recurring background pass over the deployment's repositories - a retention sweep, a vulnerability re-scan -
 * supplied by a {@link MaintenanceTaskProvider} module discovered with {@link ServiceLoader}. The server hosts one
 * neutral scheduler that owns the worker thread, the tenant and repository iteration, the single-writer lease and
 * the gauge registry; a task only says what to do per repository (and optionally per tenant), so a new pass is a
 * drop-in module with no change to the neutral scheduling.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One task instance serves the whole pass, and the scheduler fans the
 *       {@code (tenant, repository)} units out over a bounded pool - so {@link #repository} is called
 *       <em>concurrently</em> on the same instance, for different repositories, and any state it shares across units
 *       must be safe for that. {@link #tenant} runs only after every repository unit of the pass has settled, and
 *       {@link #completed} only after those.</li>
 *   <li><b>Idempotency / replay.</b> A pass is re-run on every interval and may be re-run on demand, so it must
 *       converge rather than accumulate: the same store contents must produce the same derived state. It must also
 *       back-fill from durable truth when the capability is switched on late (&sect;5) - a pass that only reacts to new
 *       writes leaves a deployment silently incomplete.</li>
 *   <li><b>Tenant scoping.</b> A unit reads and writes only through the {@link RepositoryContext}/{@link TenantContext}
 *       store it is handed, which is already scoped; reaching outside it is a cross-tenant read (&sect;6). Its
 *       <em>settings</em> are equally the unit's, and are read per pass through that same context's
 *       {@link RepositoryContext#config() config()} - resolved for the unit's own tenant, so a tenant-overridable dial
 *       takes effect where the tenant set it and a deployment-wide one resolves identically for every unit. A dial a
 *       {@link MaintenanceTaskProvider} captured from its construction-time lookup instead is deployment-global: it
 *       ignores every tenant override, silently, and it also stops tracking a setting changed after boot. A provider
 *       reads only what decides <em>whether and how often</em> the pass exists (its enablement and cadence); everything
 *       a unit's work depends on comes from the context.</li>
 *   <li><b>Error visibility.</b> A thrown failure is logged and counted on
 *       {@code jenreg.maintenance.failures}, and the pass continues with the next unit - so a failure is
 *       contained to its unit, never silent. Swallowing a failure inside a unit hides it from that counter and from
 *       the task's reported status; throw instead. A read path that degrades gracefully is <em>not</em> an exemption:
 *       a derived view whose previous generation keeps serving because the rebuild never reached its marker flip is
 *       still a pass that did not do its work, and a unit that returns normally over it makes an index unrebuilt for
 *       a week indistinguishable from a healthy one. Throwing costs nothing on the read side - the scheduler contains
 *       the failure to its unit and the previously published state stands either way - so the choice is only between
 *       a counted failure and a silent one.
 *       <p>A unit that sweeps many subjects may <em>contain</em> one subject's failure so the rest are still swept -
 *       one poisoned jar must not cost a million coordinates their pass (clause 6) - but containment is not an
 *       exemption either: the contained failures are named and raised once before the unit returns
 *       ({@link UnitFailures} is the shared idiom, {@code SignalRefreshTask} the worked example). And a unit that did
 *       not do its work may not <em>stamp</em> it: a freshness instant, a marker flip or any other "this view is
 *       current" claim is written only over a subject set that really landed, because that stamp is what every
 *       downstream read takes as the statement that the data is current (&sect;10). Stamping fresh over a failed
 *       sweep is worse than the silent return it usually accompanies - it does not merely hide the outage, it
 *       actively asserts the opposite.</li>
 *   <li><b>Ordering / concurrency (exclusion).</b> {@link #exclusion()} <em>names</em> the single-writer mechanism that
 *       owns the pass, and the declaration produces the behaviour: the scheduler holds the deployment-root lease
 *       {@code locks/<name>} for the whole pass exactly when a task declares {@link Exclusion#LEASE}, so a task cannot
 *       take a lease it said it does not need, nor ride a walk with no exclusion at all. Declaring {@code LEASE} on top
 *       of a walk's segment claim serialises the whole fleet onto one node and must be argued for, not inherited;
 *       the walk SPI states the exclusion as a clause.</li>
 *   <li><b>Bounded work / cancellation.</b> A pass has no cancellation seam: once a unit starts it runs to completion.
 *       The scheduler bounds the damage of a lease lost mid-pass by submitting no <em>further</em> units and skipping
 *       {@link #completed}, so a task must not treat {@link #completed} as guaranteed to follow {@link #repository} -
 *       a pass that stopped early must leave its derived state readable and convergent, never half-committed. A task
 *       that enumerates must page rather than materialise; a unit that could run unboundedly long keeps the fleet
 *       pinned for that long.</li>
 * </ol>
 */
public interface MaintenanceTask {

    /** The task name, e.g. {@code cleanup}, {@code scan} - also the lease object an exclusive pass locks on. */
    String name();

    /** How often a full pass should run, read from the provider's own configuration at creation. */
    Duration interval();

    /** The moment this task is next due after {@code after}: one interval on, by default; a task on a calendar
     *  schedule (a walk entry's cron expression) answers the next moment the schedule names. The scheduler asks it
     *  when it arms the task and each time the task ran, and never re-derives it from {@link #interval()}. */
    default Instant next(Instant after) {
        return after.plus(interval());
    }

    /**
     * Which single-writer mechanism owns this pass. The declaration <em>produces</em> the behaviour - the scheduler
     * takes {@code locks/<name>} exactly for {@link Exclusion#LEASE} - so a pass cannot hold a lease it declared it
     * does not need, and the answer is a reviewable statement rather than a boolean nobody re-derives. It was measured
     * as three mutually inconsistent shapes across five walk-riders before it was named.
     *
     * <p>The scheduler asks once per pass and must get the same answer every time: a task whose owner depends on what
     * is installed - the shared walk being present or absent - reads that from the state it was constructed with, so
     * the answer is derived but never changes under a running fleet.
     */
    enum Exclusion {

        /** The scheduler holds the deployment-root lease {@code locks/<name>} for the whole pass, so exactly one node
         *  in the fleet runs it. The default, and the answer for any pass that mutates shared state with no exclusion
         *  mechanism of its own. */
        LEASE,

        /** The pass rides the shared artifact walk, whose per-segment claim (holder, expiry, refuse-don't-steal) is
         *  already its single-writer owner, so the scheduler takes no task lease and the fleet parallelises on disjoint
         *  ranges. Taking the lease as well pins every node onto one and needs an argument. */
        WALK_CLAIM,

        /** The pass needs no exclusion at all: a per-node pass whose result must exist on every replica (a gauge
         *  family, a last-writer-wins idempotent upsert), so serialising it onto one node would leave the others
         *  holding nothing. Distinct from {@link #WALK_CLAIM} because the two are different reasons for the same
         *  scheduler behaviour, and a pass that holds no walk must not claim one. */
        NONE;

        /** Whether the scheduler takes the task lease for a pass declaring this - {@code true} for {@link #LEASE}
         *  alone. The one place the declaration turns into behaviour. */
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
