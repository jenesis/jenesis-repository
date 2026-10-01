package build.jenesis.repository.management.web;

import module java.base;
import module spring.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.RepositoryRouting;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import build.jenesis.repository.server.kernel.PinnedSettings;
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

    /** The walks document as the node runs it: an operator's pin over the stored value over the environment. */
    private final UnaryOperator<String> effective;

    private final MaintenanceScheduler maintenance;

    private final RepositoryRouting routing;

    public WalksAdminController(ArtifactStore root, AuditTrail audit, Settings settings, PinnedSettings pinned,
                                Environment environment, MaintenanceScheduler maintenance, RepositoryRouting routing) {
        this.root = root;
        this.audit = audit;
        this.effective = pinned.effective(settings, environment);
        this.maintenance = maintenance;
        this.routing = routing;
    }

    /** The walks: every entry with its last run, every installed consumer, every standing request. */
    @GetMapping("/api/admin/walks")
    public WalkRuns.Overview walks() throws IOException {
        return overview();
    }

    /** Ask for a walk of the store now; answers the overview, the new request among its standing ones. */
    @PostMapping("/api/admin/walks/run")
    public WalkRuns.Overview run(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                 HttpServletRequest request) throws IOException {
        WalkRuns.request(root, audit, routing.tenant(request), key == null ? "anonymous" : Authorization.hash(key));
        return overview();
    }

    private WalkRuns.Overview overview() throws IOException {
        Map<String, TaskSchedule.TaskRun> runs = maintenance.taskRuns();
        return WalkRuns.overview(effective, root, name -> lastRun(runs.get(name)),
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
