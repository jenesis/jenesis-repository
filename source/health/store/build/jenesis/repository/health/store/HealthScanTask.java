package build.jenesis.repository.health.store;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;

/**
 * The scheduled maintainer-health sweep: each repository's held coordinates are scored by the live {@link HealthSource}
 * (deps.dev, OpenSSF Scorecard) and persisted to the durable {@link HealthLedger}, so the gate and the panel read
 * stored scores rather than probing per look - the health sibling of the advisory {@code VulnerabilityScanTask}.
 *
 * <p>Health is version-independent, so each distinct {@code (ecosystem, coordinate)} is probed once per pass. A
 * coordinate the source cannot score is left unrecorded - unknown, not healthy - so a read resolves it to the same safe
 * default (no finding). With no health source enabled the provider builds no task.
 *
 * <p><strong>A sweep that could not do its work fails and does not stamp itself fresh</strong> ({@link MaintenanceTask}
 * clause 4). A failed read propagates at once; a refused upsert is contained per coordinate, then raised before the
 * unit returns. Either way the {@link HealthLedger#scanned health stamp} is not advanced: the panel reads it as "this
 * is how current the scores are".
 */
public final class HealthScanTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthScanTask.class);

    private final Duration interval;
    private final HealthSource source;
    private final Optional<HealthLedgerProvider> ledgerProvider;

    public HealthScanTask(Duration interval, HealthSource source) {
        this(interval, source, HealthLedgerProvider.installed());
    }

    /** Bind an explicit health-ledger provider (empty disables persistence) rather than discovering one through
     *  {@link HealthLedgerProvider#installed()}. */
    public HealthScanTask(Duration interval, HealthSource source, Optional<HealthLedgerProvider> ledgerProvider) {
        this.interval = interval;
        this.source = source;
        this.ledgerProvider = ledgerProvider;
    }

    @Override
    public String name() {
        return "health-scan";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** {@link MaintenanceTask.Exclusion#NONE}: the ledger writes are last-writer-wins idempotent upserts, so every node
     *  refreshes its own view and the values converge; there is no walk to claim. */
    @Override
    public Exclusion exclusion() {
        return Exclusion.NONE;
    }

    /** Probe and record one repository's maintainer health. A failed read ends the unit; a refused upsert is contained,
     *  so the rest still records, and raised at the end. The freshness stamp is written only when every record
     *  landed. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        Optional<HealthLedger> ledger = ledgerProvider.map(provider -> provider.over(context.store()));
        if (ledger.isEmpty()) {
            return;                                             // no persistence module: nothing to sweep into
        }
        HealthLedger durable = ledger.get();
        // A scored count folded over the streamed coordinates; no coordinate set is buffered.
        long[] scored = {0};
        // A stated bound: one short string per distinct (ecosystem, coordinate) for the pass, so each is probed once -
        // about a hundred megabytes at a million coordinates, which is this pass's ceiling. Versions are never held.
        Set<String> probed = new HashSet<>();
        UnitFailures failed = context.failures("The maintainer-health sweep of " + context.tenant() + '/'
                + context.repository(),
                "Those coordinates are missing this pass's health record and the health freshness stamp was "
                + "deliberately NOT advanced, so no panel reads this pass as a completed scan; the next pass "
                + "re-probes them.");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(context.store());
        // Every held version (published or cached) every Nth pass; between, only those since the last full pass.
        IncrementalPasses cadence = IncrementalPasses.over(context.store(), name(), "health/scan-passes",
                HealthLedger.scanned(context.store()), context.config());
        cadence.holdings(inventory, held -> {
            // Health is version-independent: one probe per coordinate per pass.
            if (!probed.add(held.ecosystem() + ' ' + held.coordinate())) {
                return;
            }
            Optional<Health> looked = source.health(held.ecosystem(), held.coordinate());
            if (looked.isEmpty()) {
                return;                                     // unscored: left unrecorded (unknown, not healthy)
            }
            try {
                durable.record(held.ecosystem(), held.coordinate(), looked.get(), context.now());
                scored[0]++;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Failed to persist maintainer-health of {}/{} {}; the ledger misses this pass's record for "
                        + "the coordinate, the sweep is reported FAILED and its freshness stamp is not advanced",
                        context.tenant(), context.repository(), held.coordinate(), e);
                failed.record(held.ecosystem() + ' ' + held.coordinate(), e);
            }
        });
        // Only a full pass in which every record landed stamps the freshness: the stamp claims "the scores are as of
        // now", which a pass with refused upserts would contradict and an incremental pass cannot make.
        cadence.completed(context.now(), !failed.any());
        context.gauge("jenrepo.health.scored.count",
                "Coordinates held, published or cached from an upstream, whose maintainer-health the sweep scored "
                        + "and persisted this pass",
                Map.of("tenant", context.tenant(), "repository", context.repository()), scored[0]);
    }
}
