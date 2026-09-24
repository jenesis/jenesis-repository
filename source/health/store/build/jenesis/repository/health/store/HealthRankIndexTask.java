package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled maintainer-health rank-index pass: it keeps each repository's durable weakest-first health index current
 * with the records the {@link HealthScanTask health sweep} (and an explicit rescan) persist, so the console panel serves
 * a bounded, ordered page rather than buffering and sorting every scored coordinate in heap on each render. It is the
 * index sibling of the search and dependents index passes - a derived view rebuilt from durable truth on the maintenance
 * cadence, off the request path.
 *
 * <p>Cheap in the steady state: the rebuild is a no-op whenever the records have not moved since the last build (it
 * compares the freshness stamp the index carries against the live one), so a pass that finds nothing changed writes
 * nothing. When they have moved, the records are streamed - never buffered whole - into a fresh index generation and
 * published with one atomic marker flip; a failed rebuild <em>throws</em>, so the scheduler logs it, counts it on
 * {@code jenreg.maintenance.failures} and reports the pass FAILED ({@link MaintenanceTask} clause 4). That
 * is a visibility decision, not a serving one: a rebuild that failed never reached the marker flip, so the previous
 * generation still stands and the panel keeps paging it (or, before the first successful build, reports that no ranking
 * exists yet - never a ranking derived on the request thread) - always correct, never stale. The read path degrading
 * gracefully is exactly why the failure has to be counted: an index that has not rebuilt for a week is otherwise
 * indistinguishable from a healthy one, and a repository whose FIRST build keeps failing shows an operator a panel
 * that says "not yet ranked" indefinitely, which only this counter explains. With no health-ledger module installed there is nothing
 * to index and the pass is a no-op.
 *
 * <p><strong>Exclusive.</strong> The rebuild mutates shared durable state - it reclaims a superseded generation and
 * flips the marker with a plain write, neither guarded by a compare-and-set - so it must be the <em>single writer</em>
 * across the fleet (the {@link MaintenanceTask.Exclusion#LEASE lease-owned} contract for a durable-state mutator): two
 * concurrent rebuilds could otherwise target the same generation and interleave their writes, or one reclaim the
 * generation the other just published while a reader pages it. The scheduler serialises exclusive passes through a
 * single-writer lease, so exactly one node rebuilds at a time.
 */
public final class HealthRankIndexTask implements MaintenanceTask {

    private final Duration interval;
    private final Optional<HealthLedgerProvider> ledgerProvider;

    public HealthRankIndexTask(Duration interval) {
        this(interval, HealthLedgerProvider.installed());
    }

    /** Embedding/test seam: bind an explicit health-ledger provider (empty to disable indexing) rather than discovering
     *  one through {@link HealthLedgerProvider#installed()}. */
    public HealthRankIndexTask(Duration interval, Optional<HealthLedgerProvider> ledgerProvider) {
        this.interval = interval;
        this.ledgerProvider = ledgerProvider;
    }

    @Override
    public String name() {
        return "health-rank-index";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        // A durable-state mutator (reclaims a generation, flips the marker with a plain write) must be the fleet's
        // single writer, so two rebuilds never target the same generation or one reclaim the other's live generation.
        return Exclusion.LEASE;
    }

    /** Rebuild this repository's health rank index. A failure propagates: the scheduler logs it, counts it and reports
     *  the pass FAILED (clause 4), while the un-flipped marker leaves the previous generation serving - a failed rebuild
     *  is a visible <em>write</em> failure, never a read outage. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        Optional<HealthLedger> ledger = ledgerProvider.map(provider -> provider.over(context.store()));
        if (ledger.isEmpty()) {
            return;                                             // no persistence module: nothing to index
        }
        ledger.get().reindex();
    }
}
