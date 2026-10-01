package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled maintainer-health rank-index pass: it keeps each repository's weakest-first index current with the
 * records the {@link HealthScanTask health sweep} and explicit rescans persist - a derived view rebuilt from durable
 * state off the request path.
 *
 * <p>The rebuild is a no-op when the stamp has not moved. Otherwise the records stream into a fresh generation
 * published by one marker flip; a failed rebuild throws, so the scheduler logs and counts it and reports the pass
 * FAILED ({@link MaintenanceTask} clause 4). The previous generation keeps serving - or, before the first build, the
 * panel says "not yet ranked" - so the counted failure is what tells an index that has not rebuilt for a week from a
 * healthy one. Without a health-ledger module the pass is a no-op.
 *
 * <p><strong>Exclusive</strong> ({@link MaintenanceTask.Exclusion#LEASE}): the rebuild reclaims a generation and flips
 * the marker with plain writes, so two concurrent rebuilds could interleave on one generation or reclaim the one the
 * other just published.
 */
public final class HealthRankIndexTask implements MaintenanceTask {

    private final Duration interval;
    private final Optional<HealthLedgerProvider> ledgerProvider;

    public HealthRankIndexTask(Duration interval) {
        this(interval, HealthLedgerProvider.installed());
    }

    /** Bind an explicit health-ledger provider (empty disables indexing) rather than discovering one through
     *  {@link HealthLedgerProvider#installed()}. */
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
        // Plain writes on shared state: the fleet's single writer.
        return Exclusion.LEASE;
    }

    /** Rebuild this repository's health rank index. A failure propagates and the pass reports FAILED (clause 4), while
     *  the unflipped marker leaves the previous generation serving. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        Optional<HealthLedger> ledger = ledgerProvider.map(provider -> provider.over(context.store()));
        if (ledger.isEmpty()) {
            return;                                             // no persistence module: nothing to index
        }
        ledger.get().reindex();
    }
}
