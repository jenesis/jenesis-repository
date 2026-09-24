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
 * The scheduled maintainer-health sweep: each repository's inventory is walked once per pass, every held coordinate's
 * health is looked up from the live {@link HealthSource} (deps.dev / OpenSSF Scorecard), and the answer is persisted to
 * the durable {@link HealthLedger} - the health sibling of the advisory {@code VulnerabilityScanTask}, turning "probe
 * deps.dev for maintainer-health every time the gate or a panel looks" into "persist what was scored, serve it from the
 * store". So a coordinate whose project health was never scored, or drifted since it was admitted, surfaces on the panel
 * and hardens the gate without a manual scan.
 *
 * <p>It needs no exclusion at all: the ledger writes are last-writer-wins idempotent upserts, so every node refreshes
 * its own view and the values converge. Health is version-independent, so each distinct
 * {@code (ecosystem, coordinate)} is probed and recorded once per pass rather than once per held version. A coordinate
 * the source scores nothing (no resolvable source repository, an ecosystem deps.dev does not cover) is left
 * unrecorded - unknown, not healthy - so a later read resolves it to the same safe default (no finding) rather than a
 * fabricated clean score. With no health source enabled the provider builds no task.
 *
 * <p><strong>A sweep that could not do its work fails and does not stamp itself fresh</strong> ({@link
 * MaintenanceTask} clause 4). A read that gives way propagates at once. A refused ledger upsert is contained
 * per coordinate - one coordinate must not cost the rest of the walk its pass - then named and raised before the unit
 * returns, so the outage reaches {@code jenreg.maintenance.failures} and the task's reported status.
 * Either way the {@link HealthLedger#scanned health stamp} is <em>not</em> advanced: the stamp is what the health panel reads as "this is
 * how current the scores are", and a sweep that recorded nothing while stamping itself fresh does not merely hide the
 * outage, it asserts the opposite.
 */
public final class HealthScanTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthScanTask.class);

    private final Duration interval;
    private final HealthSource source;
    private final Optional<HealthLedgerProvider> ledgerProvider;

    public HealthScanTask(Duration interval, HealthSource source) {
        this(interval, source, HealthLedgerProvider.installed());
    }

    /** Embedding/test seam: bind an explicit health-ledger provider (empty to disable persistence) rather than
     *  discovering one through {@link HealthLedgerProvider#installed()}. */
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

    /** {@link MaintenanceTask.Exclusion#NONE} rather than {@link MaintenanceTask.Exclusion#WALK_CLAIM}: this pass
     *  holds no walk to claim segments of. It needs no exclusion at all - the ledger writes are last-writer-wins
     *  idempotent upserts, so every node refreshes its own view and the values converge, and serialising the fleet
     *  onto one node would buy nothing. */
    @Override
    public Exclusion exclusion() {
        return Exclusion.NONE;
    }

    /** Probe and record one repository's maintainer-health. A failure propagates: the scheduler logs it, counts it
     *  and reports the pass FAILED (clause 4). A read that gives way ends the unit where it stands; a refused upsert
     *  is contained, so the rest of the walk still records, and raised at the end. The freshness stamp is written
     *  only over a walk in which every record landed. */
    @Override
    public void repository(RepositoryContext context) throws IOException {
        Optional<HealthLedger> ledger = ledgerProvider.map(provider -> provider.over(context.store()));
        if (ledger.isEmpty()) {
            return;                                             // no persistence module: nothing to sweep into
        }
        HealthLedger durable = ledger.get();
        // Fold a scored-count over the streamed coordinate set rather than buffering every published coordinate in
        // heap: a repository with millions of versions must not materialise them all just to probe maintainer-health.
        long[] scored = {0};
        // A stated bound: the distinct (ecosystem, coordinate) pairs probed so far, one short string each for the
        // whole pass, so each is probed once however many versions it has. A million distinct coordinates are about
        // a hundred megabytes here - the heap of a server that sets no -Xmx - which is this pass's ceiling; the
        // versions themselves are never held.
        Set<String> probed = new HashSet<>();
        UnitFailures failed = context.failures("The maintainer-health sweep of " + context.tenant() + '/'
                + context.repository(),
                "Those coordinates are missing this pass's health record and the health freshness stamp was "
                + "deliberately NOT advanced, so no panel reads this pass as a completed scan; the next pass "
                + "re-probes them.");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(context.store());
        // Every version every Nth pass; between, only the versions published since the last full one, whose
        // coordinates are the ones that may never have been scored.
        IncrementalPasses cadence = IncrementalPasses.over(context.store(), name(), "health/scan-passes",
                HealthLedger.scanned(context.store()), context.config());
        cadence.coordinates(inventory, held -> {
            // Health is version-independent, so probe each distinct (ecosystem, coordinate) once per pass rather
            // than once per held version.
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
        // A full pass in which every record landed stamps the ledger's freshness so every view shows how stale its
        // rendered health is (Principle 10) - and only that, because the stamp is the claim "the scores you are
        // reading are as of now": writing it over a walk whose upserts were refused would state the opposite of
        // what happened (clause 4), and an incremental pass cannot make the claim at all.
        cadence.completed(context.now(), !failed.any());
        context.gauge("jenreg.health.scored.count",
                "Published coordinates whose maintainer-health the sweep scored and persisted this pass",
                Map.of("tenant", context.tenant(), "repository", context.repository()), scored[0]);
    }
}
