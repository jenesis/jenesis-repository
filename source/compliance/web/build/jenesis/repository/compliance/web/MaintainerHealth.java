package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Maintainer health over one repository's store: the one implementation the API's {@code /api/health}, the console's
 * panel and, through the API, the CLI reach. The report is a page of the committed weakest-first ranking with no probe
 * and no write, so it stands when the source is down; the refresh probes every held coordinate off the request, as a
 * stored report, and builds the ranking under the pass's lease where a scheduler is at hand.
 *
 * <p>One stored report names the refresh whichever surface starts it, so a refresh asked for on the console and one
 * asked for over the API are the same walk rather than two at once.
 */
public final class MaintainerHealth {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintainerHealth.class);

    /** The stored report a refresh runs under, one per repository, so two refreshes asked at once start one walk. */
    public static final String REFRESH_REPORT = "health-refresh";

    private final HealthLedgerProvider ledgers;
    // Consulted only on a refresh; none() when no source is enabled, so nothing is persisted.
    private final Supplier<HealthSource> source;
    /** The scheduler, so a refresh can build the ranking under the pass's lease; without one the ranking waits. */
    private final Supplier<MaintenanceScheduler> maintenance;

    /** @param maintenance resolved at use, since resolving the scheduler bean early would start its workers; a
     *                    supplier answering {@code null} leaves the ranking to the scheduled pass. */
    public MaintainerHealth(HealthLedgerProvider ledgers, Supplier<HealthSource> source,
                            Supplier<MaintenanceScheduler> maintenance) {
        this.ledgers = ledgers;
        this.source = source;
        this.maintenance = maintenance;
    }

    /**
     * Start the refresh of {@code store}'s repository off the request: each distinct held coordinate probed once and
     * its score upserted, the freshness stamped and the ranking built. {@code bind} decorates the pass before it is
     * handed to its thread - the seam a caller that resolves its tenant from the request binds it through. Answers
     * whether this call started it rather than finding one running.
     */
    public boolean refresh(ArtifactStore store, UnaryOperator<StoredReport.Pass> bind) throws IOException {
        HealthLedger ledger = ledgers.over(store);
        return StoredReport.compute(store, REFRESH_REPORT, bind.apply(() -> {
            Instant now = Instant.now();
            probe(store, ledger, now);
            HealthLedger.scanned(store).mark(Instant.now());
            rank(store, ledger);
            return StoredReport.Rows.of(List.of("refreshed " + now));
        }));
    }

    /** Whether a refresh of {@code store}'s repository is running now. */
    public boolean refreshing(ArtifactStore store) throws IOException {
        return StoredReport.read(store, REFRESH_REPORT).map(StoredReport.Report::running).orElse(false);
    }

    /**
     * One page of {@code store}'s committed ranking, weakest first, resumed after {@code cursor}; before a ranking is
     * committed the report says so, with no entries, rather than ranking whatever the ledger buffers.
     */
    public Report report(ArtifactStore store, String cursor, int limit) throws IOException {
        boolean refreshing = refreshing(store);
        return switch (ledgers.over(store).worstFirst(cursor, limit)) {
            case HealthLedger.Ranking.NotBuilt notBuilt ->
                    new Report(true, false, null, null, 0, notBuilt.scannedAt().orElse(null), refreshing);
            case HealthLedger.Ranking.Ranked ranked -> {
                List<Entry> entries = new ArrayList<>(ranked.entries().size());
                for (HealthLedger.Located located : ranked.entries()) {
                    entries.add(Entry.of(located));
                }
                // The ranking's build instant, never the live stamp a later sweep advanced.
                yield new Report(true, true, entries, ranked.nextCursor(), ranked.total(),
                        ranked.scannedAt().orElse(null), refreshing);
            }
        };
    }

    /** The report of a deployment without the health-ledger module. */
    public static Report unavailable() {
        return new Report(false, false, null, null, 0, null, false);
    }

    /**
     * Builds the ranking a refresh just fed, at most one build at a time across the deployment, under the
     * {@code health-rank-index} lease through {@link MaintenanceScheduler#exclusively}. A refused lease means another
     * builder is at work, and a failure leaves the persisted scores for the next pass; neither fails the refresh.
     */
    private void rank(ArtifactStore store, HealthLedger ledger) {
        MaintenanceScheduler scheduler = maintenance.get();
        if (scheduler == null) {
            return;   // no scheduler at hand: the refresh persists and the ranking waits for whatever runs one
        }
        try {
            scheduler.exclusively("health-rank-index", Instant.now(), () -> {
                ledger.reindex();
                return Boolean.TRUE;
            });
        } catch (IOException | RuntimeException degraded) {
            LOGGER.warn("A health refresh of {} persisted its scores but could not rebuild the ranking; the scheduled "
                    + "health-rank-index pass folds them on its next run.", store, degraded);
        }
    }

    /** Each distinct held {@code (ecosystem, coordinate)} probed once and its score upserted, as the scheduled sweep
     *  does; one the source cannot score stays unrecorded. Best-effort per coordinate. */
    private void probe(ArtifactStore store, HealthLedger ledger, Instant now) throws IOException {
        HealthSource asked = source.get();
        if (asked == HealthSource.none()) {
            return;                                             // no live source enabled: nothing to re-probe
        }
        Set<String> probed = new HashSet<>();
        // Streamed over everything the repository holds, its cached copies too, as the scheduled pass is.
        new StoreRepositoryInventory(store).holdings(held -> {
            if (!probed.add(held.ecosystem() + ' ' + held.coordinate())) {
                return;                                         // health is version-independent: probe each once
            }
            Optional<Health> looked;
            try {
                looked = asked.health(held.ecosystem(), held.coordinate());
            } catch (RuntimeException _) {
                return;                                         // the live source degrades to empty on its own
            }
            if (looked.isEmpty()) {
                return;
            }
            try {
                ledger.record(held.ecosystem(), held.coordinate(), looked.get(), now);
            } catch (IOException | RuntimeException _) {
                // best-effort: the sweep persists the coordinate on its next pass
            }
        });
    }

    /**
     * The health report. {@code available} is false without the health-ledger module. {@code ranked} says whether a
     * ranking is built; when it is not, {@code entries} is {@code null} rather than empty, so no reader can take it for
     * "nothing is unhealthy". {@code lastScanned} is the ranking's build instant when ranked, the ledger's last sweep
     * otherwise, {@code null} for never scanned. {@code refreshing} says a refresh is running, so the report is the one
     * it will replace - what a caller watching it polls on.
     */
    public record Report(boolean available, boolean ranked, List<Entry> entries, String nextCursor, int total,
                         Instant lastScanned, boolean refreshing) {
    }

    /** One coordinate's stored health: the overall score and three components ({@code -1} for one the source could not
     *  evaluate), the source repository scored and when. */
    public record Entry(String ecosystem, String coordinate, String sourceRepository, double overall,
                        double maintenance, double review, double provenance, String scannedAt) {

        static Entry of(HealthLedger.Located located) {
            Health health = located.health();
            return new Entry(located.ecosystem(), located.coordinate(), health.sourceRepository(),
                    health.overall(), health.maintenance(), health.review(), health.provenance(),
                    located.scannedAt().toString());
        }
    }
}
