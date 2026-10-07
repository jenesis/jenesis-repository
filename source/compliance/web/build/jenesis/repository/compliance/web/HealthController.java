package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/health?repo=}: the Scorecard-style maintainer health persisted for a repository's coordinates, read
 * from the {@code HealthLedger} alone - a bounded page of the committed ranking with no probe and no write, so it stands
 * when the source is down. {@code lastScanned} ({@code HealthLedger.scanned}) is {@code null} for never scanned, which a
 * client renders as such. {@code refresh=true} is the explicit write: it re-probes the {@link HealthSource} per held
 * coordinate and upserts the scores, as the scheduled sweep does. Gated {@code manage:read}; an unsafe name is a
 * {@code 400}, and without the health-ledger module the answer is {@code 501}. The work is {@link MaintainerHealth}'s,
 * which the console's panel reaches too.
 *
 * <p>The weakest-first ranking is built by the {@code health-rank-index} pass under its lease. Until one is committed
 * the endpoint answers {@code 503} with {@code "ranked":false}, {@code "entries":null} and the ledger's
 * {@code lastScanned}, rather than ranking on the request thread whatever the ledger buffers. The gate's point lookups
 * ({@code HealthLedger.health}) need no ranking.
 */
@RestController
public class HealthController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    // Empty without the health-ledger module, which answers 501.
    private final Optional<MaintainerHealth> health;

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
        this.health = health.map(ledgers -> new MaintainerHealth(ledgers, source, maintenance));
    }

    /** Says, on a refreshed read, whether this request started the walk or found one already running. */
    public static final String REFRESH_HEADER = "Jenesis-Refresh";

    @GetMapping("/api/health")
    @ResponseBody
    public MaintainerHealth.Report health(@RequestParam("repo") String repo,
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
        if (refresh) {
            // A probe per coordinate, so it runs as a stored report off the request; the answer is the ledger as it
            // stands, with a header saying whether this request started the refresh.
            boolean started = health.get().refresh(store, UnaryOperator.identity());
            response.setHeader(REFRESH_HEADER, started ? "started" : "running");
        }
        // A page of the committed ranking, never one derived here; read after a refresh is started, so the answer
        // that started one already says so.
        MaintainerHealth.Report report = health.get().report(store, after.isBlank() ? null : after,
                Math.max(1, Math.min(limit, MAX_PAGE)));
        if (!report.ranked()) {
            response.setStatus(503);    // beside null entries, so a client ignoring `ranked` cannot read "all healthy"
        }
        return report;
    }

    /** The largest page served; a caller past it follows {@code next}, passed back as {@code after}. */
    private static final int MAX_PAGE = 5000;
}
