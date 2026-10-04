package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/health?repo=}: the Scorecard-style maintainer health persisted for a repository's coordinates, read
 * from the {@link HealthLedger} alone - a bounded page of the committed ranking with no probe and no write, so it stands
 * when the source is down. {@code lastScanned} ({@link HealthLedger#scanned}) is {@code null} for never scanned, which a
 * client renders as such. {@code refresh=true} is the explicit write: it re-probes the {@link HealthSource} per held
 * coordinate and upserts the scores, as the scheduled sweep does. Gated {@code manage:read}; an unsafe name is a
 * {@code 400}, and without the health-ledger module the answer is {@code 501}.
 *
 * <p>The weakest-first ranking is built by the {@code health-rank-index} pass under its lease. Until one is committed
 * the endpoint answers {@code 503} with {@code "ranked":false}, {@code "entries":null} and the ledger's
 * {@code lastScanned}, rather than ranking on the request thread whatever the ledger buffers. The gate's point lookups
 * ({@link HealthLedger#health}) need no ranking.
 */
@RestController
public class HealthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthController.class);

    private final Repositories repositories;
    private final RepositoryRouting routing;
    // Empty without the health-ledger module, which answers 501.
    private final Optional<HealthLedgerProvider> health;
    // Consulted only on an explicit refresh; none() when no source is enabled, so nothing is persisted.
    private final Supplier<HealthSource> source;
    /** The scheduler, so a refresh can build the ranking under the pass's lease; without one the ranking waits. */
    private final Supplier<MaintenanceScheduler> maintenance;

    public HealthController(Repositories repositories, RepositoryRouting routing, Supplier<HealthSource> source,
                            Supplier<MaintenanceScheduler> maintenance) {
        this(repositories, routing, source, HealthLedgerProvider.installed(), maintenance);
    }

    /** With an explicit health-ledger provider, empty for none, rather than {@link HealthLedgerProvider#installed()}. */
    public HealthController(Repositories repositories, RepositoryRouting routing, Supplier<HealthSource> source,
                            Optional<HealthLedgerProvider> health) {
        this(repositories, routing, source, health, () -> null);
    }

    /** @param maintenance resolved at use, since resolving the scheduler bean here would start its workers early. */
    public HealthController(Repositories repositories, RepositoryRouting routing, Supplier<HealthSource> source,
                            Optional<HealthLedgerProvider> health, Supplier<MaintenanceScheduler> maintenance) {
        this.repositories = repositories;
        this.routing = routing;
        this.source = source;
        this.health = health;
        this.maintenance = maintenance;
    }

    /** The stored report an explicit refresh runs under, one per repository, so two refreshes asked at once start
     *  one walk. */
    public static final String REFRESH_REPORT = "health-refresh";
    /** Says, on a refreshed read, whether this request started the walk or found one already running. */
    public static final String REFRESH_HEADER = "Jenesis-Refresh";

    @GetMapping("/api/health")
    @ResponseBody
    public HealthReport health(@RequestParam("repo") String repo,
                               @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
                               @RequestParam(value = "after", defaultValue = "") String after,
                               @RequestParam(value = "limit", defaultValue = "500") int limit,
                               HttpServletRequest http,
                               HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = routing.tenant(http);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        if (health.isEmpty()) {
            response.setStatus(501);
            return null;
        }
        ArtifactStore store = repositories.store(tenant, repo);
        HealthLedger ledger = health.get().over(store);
        if (refresh) {
            // A probe per coordinate, so it runs as a stored report off the request; the answer is the ledger as it
            // stands, with a header saying whether this request started the refresh.
            boolean started = StoredReport.compute(store, REFRESH_REPORT, () -> {
                Instant now = Instant.now();
                refreshLedger(ledger, tenant, repo, now);
                HealthLedger.scanned(store).mark(Instant.now());
                rank(store, ledger);
                return StoredReport.Rows.of(List.of("refreshed " + now));
            });
            response.setHeader(REFRESH_HEADER, started ? "started" : "running");
        }
        // A page of the committed ranking, never one derived here.
        int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
        String cursor = after.isBlank() ? null : after;
        return switch (ledger.worstFirst(cursor, pageLimit)) {
            // 503 and null entries, so a client ignoring `ranked` cannot read "nothing is unhealthy".
            case HealthLedger.Ranking.NotBuilt notBuilt -> {
                response.setStatus(503);
                yield new HealthReport(true, false, null, null, 0, notBuilt.scannedAt().orElse(null));
            }
            case HealthLedger.Ranking.Ranked ranked -> {
                List<HealthEntryView> entries = new ArrayList<>(ranked.entries().size());
                for (HealthLedger.Located located : ranked.entries()) {
                    entries.add(HealthEntryView.of(located));
                }
                // The ranking's build instant, never the live stamp a later sweep advanced.
                yield new HealthReport(true, true, entries, ranked.nextCursor(), ranked.total(),
                        ranked.scannedAt().orElse(null));
            }
        };
    }

    /** The largest page served; a caller past it follows {@code nextCursor}. */
    private static final int MAX_PAGE = 5000;

    /**
     * Builds the ranking on an explicit refresh, so a deployment with {@code scheduled-scan} off is not unranked for
     * ever, under the {@code health-rank-index} lease through {@link MaintenanceScheduler#exclusively}. A refused lease
     * means another builder is at work, and a failure leaves the persisted scores for the next pass; neither fails the
     * refresh.
     */
    private void rank(ArtifactStore store, HealthLedger ledger) {
        MaintenanceScheduler scheduler = maintenance.get();
        if (scheduler == null) {
            return;   // no scheduler on this node: the refresh persists and the ranking waits for whatever runs one
        }
        try {
            scheduler.exclusively("health-rank-index", Instant.now(), () -> {
                ledger.reindex();
                return Boolean.TRUE;
            });
        } catch (IOException | RuntimeException degraded) {
            LOGGER.warn("An explicit health refresh persisted its scores but could not rebuild the ranking; the "
                    + "scheduled health-rank-index pass folds them on its next run.", degraded);
        }
    }

    /** The {@code refresh=true} write path: each distinct held {@code (ecosystem, coordinate)} probed once and its
     *  score upserted, as the scheduled sweep does; one the source cannot score stays unrecorded. Best-effort per
     *  coordinate. */
    private void refreshLedger(HealthLedger ledger, String tenant, String repo, Instant now) throws IOException {
        HealthSource source = this.source.get();
        if (source == HealthSource.none()) {
            return;                                             // no live source enabled: nothing to re-probe
        }
        Set<String> probed = new HashSet<>();
        // Streamed over everything the repository holds, its cached copies too, as the scheduled pass is.
        new StoreRepositoryInventory(repositories.store(tenant, repo)).holdings(held -> {
            if (!probed.add(held.ecosystem() + ' ' + held.coordinate())) {
                return;
            }
            Optional<Health> looked;
            try {
                looked = source.health(held.ecosystem(), held.coordinate());
            } catch (RuntimeException _) {
                return;                                         // the live source degrades to empty on its own; a throw defers
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
     * The health report. {@code ranked} says whether a ranking is built; when it is not, {@code entries} is
     * {@code null} rather than empty, beside the {@code 503}. {@code lastScanned} is the ranking's build instant when
     * ranked, the ledger's last sweep otherwise, {@code null} for never scanned.
     */
    public record HealthReport(boolean available, boolean ranked, List<HealthEntryView> entries, String nextCursor,
                               int total, Instant lastScanned) {
    }

    /** One coordinate's stored health: the overall score and three components ({@code -1} for one the source could not
     *  evaluate), the source repository scored and when. */
    public record HealthEntryView(String ecosystem, String coordinate, String sourceRepository, double overall,
                                  double maintenance, double review, double provenance, String scannedAt) {

        static HealthEntryView of(HealthLedger.Located located) {
            Health health = located.health();
            return new HealthEntryView(located.ecosystem(), located.coordinate(), health.sourceRepository(),
                    health.overall(), health.maintenance(), health.review(), health.provenance(),
                    located.scannedAt().toString());
        }
    }
}
