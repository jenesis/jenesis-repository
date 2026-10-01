package build.jenesis.repository.findings.store;

import module java.base;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled findings-filter-index pass: it keeps each repository's facet index current with the findings the scan
 * sweep, the gate and on-demand reports persist - a derived view rebuilt from durable state off the request path, like
 * the health and vulnerability rank indexes.
 *
 * <p>The rebuild is a no-op when the composite stamp has not moved. When it has, the findings stream into a fresh
 * generation published by one marker flip; a failed rebuild throws, so the scheduler logs and counts it and reports the
 * pass FAILED ({@link MaintenanceTask} clause 4). The previous generation still serves, so the failure must be counted:
 * an index that has not rebuilt for a week otherwise looks healthy. Without a findings-persistence module the pass is a
 * no-op.
 *
 * <p><strong>Exclusive</strong> ({@link MaintenanceTask.Exclusion#LEASE}): the rebuild reclaims a generation and flips
 * the marker with plain writes, so two concurrent rebuilds could interleave on one generation or reclaim the one the
 * other just published.
 */
public final class FindingsFilterIndexTask implements MaintenanceTask {

    private final Duration interval;
    private final Optional<FindingsProvider> ledgerProvider;

    public FindingsFilterIndexTask(Duration interval) {
        this(interval, FindingsProvider.installed());
    }

    /** Bind an explicit findings provider (empty disables indexing) rather than discovering one through
     *  {@link FindingsProvider#installed()}. */
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
        // Plain writes on shared state: the fleet's single writer.
        return Exclusion.LEASE;
    }

    /** Rebuild this repository's filter index. A failure propagates and the pass reports FAILED (clause 4), while the
     *  unflipped marker leaves the previous generation serving. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        Optional<Findings> ledger = ledgerProvider.map(provider -> provider.over(context.store()));
        if (ledger.isEmpty()) {
            return;                                             // no persistence module: nothing to index
        }
        ledger.get().reindex();
    }
}
