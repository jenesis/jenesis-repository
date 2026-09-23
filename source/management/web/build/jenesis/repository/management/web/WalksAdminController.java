package build.jenesis.repository.management.web;

import module java.base;
import module spring.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.TaskSchedule;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.task.WalkRuns;

/**
 * The walks over the API: the overview the console screen renders - the scheduled entries with their consumers,
 * what each entry's last run cost, the installed consumers with their descriptions and dials, the standing
 * requests - and a request for a walk now. Both go through {@link WalkRuns}, the same implementation the screen
 * reaches; the walks document itself is edited as the {@code walks} setting through {@code /api/settings}.
 */
@RestController
public class WalksAdminController {

    private final ArtifactStore root;

    private final AuditTrail audit;

    private final Settings settings;

    private final MaintenanceScheduler maintenance;

    private final String operatorTenant;

    public WalksAdminController(ArtifactStore root, AuditTrail audit, Settings settings,
                                MaintenanceScheduler maintenance, RepositoryProperties properties) {
        this.root = root;
        this.audit = audit;
        this.settings = settings;
        this.maintenance = maintenance;
        this.operatorTenant = properties.getOperatorTenant().isBlank()
                ? properties.getDefaultTenant()
                : properties.getOperatorTenant();
    }

    /** The walks: every entry with its last run, every installed consumer, every standing request. */
    @GetMapping("/api/admin/walks")
    public WalkRuns.Overview walks() throws IOException {
        return overview();
    }

    /** Ask for a walk of the store now; answers the overview, the new request among its standing ones. */
    @PostMapping("/api/admin/walks/run")
    public WalkRuns.Overview run(@RequestHeader(value = Repositories.KEY, required = false) String key)
            throws IOException {
        WalkRuns.request(root, audit, operatorTenant, key == null ? "anonymous" : Authorization.hash(key));
        return overview();
    }

    private WalkRuns.Overview overview() throws IOException {
        Map<String, TaskSchedule.TaskRun> runs = maintenance.taskRuns();
        return WalkRuns.overview(key -> settings.getOrDefault(key, null), root, name -> lastRun(runs.get(name)),
                Instant.now());
    }

    /** The scheduler's record of a run as the overview carries it; empty until the entry has run on this node. */
    static Optional<WalkRuns.LastRun> lastRun(TaskSchedule.TaskRun run) {
        if (run == null || run.finished() == null) {
            return Optional.empty();
        }
        return Optional.of(new WalkRuns.LastRun(run.finished(), run.duration(), run.failed(), run.reads(),
                run.writes()));
    }
}
