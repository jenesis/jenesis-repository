package build.jenesis.repository.findings.store;

import module java.base;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled findings-filter-index pass: it keeps each repository's durable inverted index over the selective filter
 * facets (severity, kind, category, source) current with the findings the scan sweep, the gate and on-demand reports
 * persist, so a selective {@code /api/findings} query seeks a facet bucket rather than scanning the whole findings plane
 * and reading every coordinate's metadata document. It is the index sibling of the health and vulnerability rank-index
 * passes - a derived view rebuilt from durable truth on the maintenance cadence, off the request path.
 *
 * <p>Cheap in the steady state: the rebuild is a no-op whenever the findings have not moved since the last build (it
 * compares the composite freshness stamp - scan freshness plus eviction epoch - the index carries against the live one),
 * so a pass that finds nothing changed writes nothing. When they have moved, the findings are streamed - never buffered
 * whole - into a fresh index generation and published with one atomic marker flip; a failed rebuild <em>throws</em>, so
 * the scheduler logs it, counts it on {@code jenreg.maintenance.failures} and reports the pass FAILED
 * ({@link MaintenanceTask} clause 4). That is a visibility decision, not a serving one: a rebuild that failed never
 * reached the marker flip, so the previous generation still stands and a selective query keeps paging it (or falls back
 * to the live walk before the first build) - always correct, never stale. The read path degrading gracefully is exactly
 * why the failure has to be counted: an index that has not rebuilt for a week is otherwise indistinguishable from a
 * healthy one. With no findings-persistence module installed there is nothing to index and the pass is a no-op.
 *
 * <p><strong>Exclusive.</strong> The rebuild mutates shared durable state - it reclaims a superseded generation and
 * flips the marker with a plain write, neither guarded by a compare-and-set - so it must be the <em>single writer</em>
 * across the fleet (the {@link MaintenanceTask.Exclusion#LEASE lease-owned} contract for a durable-state mutator): two
 * concurrent rebuilds could otherwise target the same generation and interleave their writes, or one reclaim the
 * generation the other just published while a reader pages it.
 */
public final class FindingsFilterIndexTask implements MaintenanceTask {

    private final Duration interval;
    private final Optional<FindingsProvider> ledgerProvider;

    public FindingsFilterIndexTask(Duration interval) {
        this(interval, FindingsProvider.installed());
    }

    /** Embedding/test seam: bind an explicit findings provider (empty to disable indexing) rather than discovering one
     *  through {@link FindingsProvider#installed()}. */
    public FindingsFilterIndexTask(Duration interval, Optional<FindingsProvider> ledgerProvider) {
        this.interval = interval;
        this.ledgerProvider = ledgerProvider;
    }

    @Override
    public String name() {
        return "findings-filter-index";
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

    /** Rebuild this repository's filter index. A failure propagates: the scheduler logs it, counts it and reports the
     *  pass FAILED (clause 4), while the un-flipped marker leaves the previous generation serving - a failed rebuild is
     *  a visible <em>write</em> failure, never a read outage. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        Optional<Findings> ledger = ledgerProvider.map(provider -> provider.over(context.store()));
        if (ledger.isEmpty()) {
            return;                                             // no persistence module: nothing to index
        }
        ledger.get().reindex();
    }
}
