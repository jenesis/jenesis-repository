package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The durable maintainer-health surface: {@code GET /api/health?repo=} renders the OpenSSF Scorecard-style health the
 * scheduled sweep (or an explicit refresh) already persisted for each of a repository's coordinates - the health
 * sibling of {@code /api/vulnerabilities}, and its exact Principle-10 shape. The default {@code GET} reads the durable
 * {@link HealthLedger} alone: a bounded page of the ranking committed over the repository's {@code health/} key tree,
 * no deps.dev probe and no ledger write on the read path, so the panel stands when deps.dev is unreachable and a read
 * never pays for a scan a write path should have done. The instant the health was last swept is carried as {@code lastScanned}
 * ({@link HealthLedger#scanned health stamp}); {@code null} means the repository's health was never scanned, which a client renders as such
 * rather than as "healthy" - an empty panel is "never scanned", not "all projects well-maintained".
 *
 * <p>Refetching from the live source is the explicit write action: {@code refresh=true} re-probes the live
 * {@link HealthSource} for each held coordinate, upserts what it scores into the ledger and re-stamps the freshness -
 * exactly as the scheduled sweep does, so an operator can force a live re-scan without waiting for the next sweep, after
 * which the same durable render serves it. The refresh is an idempotent upsert (a re-fetch renews facts and freshness,
 * never duplicating a coordinate), mirroring the vulnerability report's {@code refresh=true}. Gated {@code manage:read}
 * by the security chain before the request is reached (the same authority the vulnerability report's refresh carries; a
 * caller without the write role reaches the console panel's read but is not offered its rescan button); a
 * traversal-unsafe repository or tenant name is a {@code 400}, and a deployment without the health-ledger module answers
 * {@code 501} so absence never reads as "no health".
 *
 * <p><strong>The weakest-first ranking is a built artefact, and this surface waits for it.</strong> The ordering comes
 * from the scheduled {@code health-rank-index} pass, which commits it under a single-writer lease; until that pass has
 * committed one, and no {@code refresh=true} has built one - every deployment's first window - the endpoint answers
 * {@code 503} with {@code "ranked":false}, {@code "entries":null} and the ledger's own {@code lastScanned}. It does not
 * derive a ranking here to fill the gap: a weakest-first list computed on the request thread out of whatever the ledger
 * buffers reads as authoritative while being a sample, and an operator reading "worst first" concludes nothing worse
 * exists (&sect;9's silent fallback). The point lookups the gate reads ({@link HealthLedger#health}) need no ranking and
 * are unaffected; only this ranked view waits.
 */
@RestController
public class HealthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthController.class);

    private final Repositories repositories;
    // The durable health ledger is a discovered optional module; empty when absent, so the endpoint answers 501.
    private final Optional<HealthLedgerProvider> health;
    // The live health source, consulted only on an explicit refresh (never the default read); HealthSource.none() when
    // no source is enabled, so a refresh then persists nothing rather than fabricating a score.
    private final HealthSource source;
    /** The scheduler, only so an explicit refresh can build the ranking under the same single-writer lease the
     *  scheduled pass takes; empty in an embedding that runs no scheduler, where the refresh persists as before and
     *  the ranking waits. */
    private final Supplier<MaintenanceScheduler> maintenance;

    public HealthController(Repositories repositories, HealthSource source,
                            Supplier<MaintenanceScheduler> maintenance) {
        this(repositories, source, HealthLedgerProvider.installed(), maintenance);
    }

    /** Embedding/test seam: bind an explicit health-ledger provider (empty to disable the read-through) rather than
     *  discovering one through {@link HealthLedgerProvider#installed()}. */
    public HealthController(Repositories repositories, HealthSource source, Optional<HealthLedgerProvider> health) {
        this(repositories, source, health, () -> null);
    }

    /** @param maintenance resolved <em>at use</em>, never here: the scheduler bean is {@code initMethod = "start"},
     *  so pulling it during this controller's construction would start its workers earlier in context startup than
     *  the deployment intends. A supplier keeps the bean graph's ordering exactly as it was before the on-demand
     *  rank build existed. */
    public HealthController(Repositories repositories, HealthSource source, Optional<HealthLedgerProvider> health,
                            Supplier<MaintenanceScheduler> maintenance) {
        this.repositories = repositories;
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
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
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
            // The explicit write path (never the default read): re-probe the live source for each held coordinate and
            // upsert what it scores, then re-stamp the freshness exactly as the scheduled sweep does. Idempotent, so a
            // re-fetch renews a coordinate's health rather than duplicating it. It is a walk of every coordinate with
            // a lookup each, so it runs as a stored report started by this request and read back by the next one,
            // never on the request thread: the refresh-walk canary measured the inline shape as a request that
            // never answered over a million coordinates. The answer below is the ledger as it stands, with a header
            // saying the refresh was started - or was already under way.
            boolean started = StoredReport.compute(store, REFRESH_REPORT, () -> {
                Instant now = Instant.now();
                refreshLedger(ledger, tenant, repo, now);
                HealthLedger.scanned(store).mark(Instant.now());
                rank(store, ledger);
                return StoredReport.Rows.of(List.of("refreshed " + now));
            });
            response.setHeader(REFRESH_HEADER, started ? "started" : "running");
        }
        // One weakest-first bounded page of the ranking the scheduled rank-index pass committed - never a ranking
        // derived here, on either the default read or the refresh: an explicit refresh re-probes and persists (above),
        // and the pass folds those records into the ranking on its own cadence. Before that first pass there is no
        // ranking at all, and this surface says so instead of answering an empty page.
        int pageLimit = Math.max(1, Math.min(limit, MAX_PAGE));
        String cursor = after.isBlank() ? null : after;
        return switch (ledger.worstFirst(cursor, pageLimit)) {
            // 503, and entries NULL rather than empty: a client that ignores `ranked` must not be able to render this
            // as "no project is unhealthy". The same answer /api/dependents gives for the same concern (an index whose
            // sweep has not committed), with the ledger's own last sweep carried so Principle 10's staleness survives
            // the refusal - "swept at X but not yet ranked" is a different fact from "nothing has ever run here".
            case HealthLedger.Ranking.NotBuilt notBuilt -> {
                response.setStatus(503);
                yield new HealthReport(true, false, null, null, 0, notBuilt.scannedAt().orElse(null));
            }
            case HealthLedger.Ranking.Ranked ranked -> {
                List<HealthEntryView> entries = new ArrayList<>(ranked.entries().size());
                for (HealthLedger.Located located : ranked.entries()) {
                    entries.add(HealthEntryView.of(located));
                }
                // The instant the rendered ranking was built at; null means the ranking was built over a repository
                // that had never been swept, which a client must render as such rather than as "healthy" (Principle
                // 10). It is deliberately NOT the live stamp: labelling a generation with a scan instant a later sweep
                // advanced would claim health that generation never observed - exactly the split the /api/findings and
                // /api/vulnerabilities siblings apply. No write on the default read path.
                yield new HealthReport(true, true, entries, ranked.nextCursor(), ranked.total(),
                        ranked.scannedAt().orElse(null));
            }
        };
    }

    /** The largest {@code /api/health} page served, so a caller's {@code limit} cannot ask the ledger to materialise an
     *  unbounded window; a caller past it follows the {@code nextCursor}. */
    private static final int MAX_PAGE = 5000;

    /**
     * Build the ranking on an explicit refresh - the answer to "a deployment with {@code scheduled-scan} off reads
     * <em>not yet ranked</em> permanently" (D-138).
     *
     * <p>This used to be left to the scheduled pass on purpose, and the reason still holds: the rank index mutates
     * shared durable state and must stay on {@code HealthRankIndexTask}'s single-writer lease rather than racing a
     * scheduled pass or a sibling rescan. What was missing was not the constraint but the way to honour it on a
     * request. {@link MaintenanceScheduler#exclusively} takes exactly that lease by name, and because
     * {@code exclusivePass} locks on the task's own name, running the reindex under {@code health-rank-index} is
     * serialised against the scheduled pass by construction - nothing here duplicates the scheduler's serialisation.
     * The on-demand cleanup endpoint has run this way for as long as it has existed.
     *
     * <p>A refused acquisition is <em>not</em> an error: another node, or the scheduled pass, is building the very
     * ranking this request asked for. The refresh has already persisted its scores either way, so the answer below is
     * simply whatever ranking currently stands - which is what the caller would have got anyway, one pass later.
     *
     * <p>Best-effort by the same logic (&sect;4): a ranking that fails to build leaves the persisted scores intact and
     * the next pass folds them, so it must not fail a refresh that already did its durable work.
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

    /** The explicit on-demand write path, run only for {@code refresh=true} - never on the default read: for each
     *  distinct held coordinate re-probe the live health source and upsert what it scores into the ledger (a coordinate
     *  the source scores nothing is left unrecorded - unknown, not healthy), exactly as the scheduled sweep does, so an
     *  operator can force a live re-scan. Health is version-independent, so each {@code (ecosystem, coordinate)} is
     *  probed once. Best-effort per coordinate: a failed probe or write only costs a re-scan next time, never a wrong
     *  answer or a failed request. */
    private void refreshLedger(HealthLedger ledger, String tenant, String repo, Instant now) throws IOException {
        if (source == HealthSource.none()) {
            return;                                             // no live source enabled: nothing to re-probe
        }
        Set<String> probed = new HashSet<>();
        // Streamed over the coordinate walk rather than a buffered coordinate list: re-probing a repository of millions
        // of versions must not materialise the whole fleet in heap just to refresh its maintainer-health.
        new StoreRepositoryInventory(repositories.store(tenant, repo)).coordinates(held -> {
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
     * The durable health report. {@code available} distinguishes an installed-but-empty ledger from the {@code 501} an
     * uninstalled module answers; {@code ranked} says whether a weakest-first ranking has been built for this
     * repository at all.
     *
     * <p><strong>{@code entries} is {@code null}, not {@code []}, when {@code ranked} is false</strong>, and the
     * response carries {@code 503}. A ranking that has not been built has no rows - not zero rows - and a client that
     * iterated an empty array here would tell its reader that no project is unhealthy, which is the one claim that
     * cannot be made before the first rank-index pass. Both signals point the same way on purpose: the status for a
     * client that reads statuses, the {@code null} for one that reaches straight for the list.
     *
     * <p>{@code lastScanned} is the instant this repository's health was last refreshed against the live source
     * (Principle 10's staleness signal), {@code null} when it was never scanned - rendered as such, never as
     * "healthy". On a ranked report it is the instant the <em>ranking</em> was built at, never a later sweep the
     * ranking has not folded in; on an unranked one it is the ledger's own last sweep, so an operator can tell
     * "swept but not yet ranked" from "nothing has ever run here".
     */
    public record HealthReport(boolean available, boolean ranked, List<HealthEntryView> entries, String nextCursor,
                               int total, Instant lastScanned) {
    }

    /** One coordinate's stored maintainer-health: the overall Scorecard score and the three component signals
     *  (a component the source could not evaluate is {@code -1}, told apart from a real zero), the source repository the
     *  score was computed on, and the instant it was scored. */
    public record HealthEntryView(String ecosystem, String coordinate, String sourceRepository, double overall,
                                  double maintenance, double review, double provenance, String scannedAt) {

        private static HealthEntryView of(HealthLedger.Located located) {
            Health health = located.health();
            return new HealthEntryView(located.ecosystem(), located.coordinate(), health.sourceRepository(),
                    health.overall(), health.maintenance(), health.review(), health.provenance(),
                    located.scannedAt().toString());
        }
    }
}
