package build.jenesis.repository.walk.web;

import module java.base;
import module spring.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.TaskSchedule;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.task.WalkRuns;
import build.jenesis.repository.walk.task.WalkSchedules;

/**
 * The walks screen. It renders the overview {@link WalkRuns} assembles - the scheduled entries with what each one's
 * last run cost, the installed consumers with their descriptions and the dials that govern them, the standing
 * requests - and edits the {@code walks} document one entry at a time: an entry is saved with its cron expression,
 * its switch and the consumers that ride it, or removed. A document that would not read back - a malformed cron
 * expression, a consumer nothing installs - is refused with the entry and the field named and nothing is written.
 * A walk now goes through the same request the admin endpoint records.
 */
@Controller
public class WalksController {

    private final Settings settings;

    private final ArtifactStore root;

    private final AuditTrail audit;

    private final MaintenanceScheduler maintenance;

    private final String operatorTenant;

    public WalksController(Settings settings, ArtifactStore root, AuditTrail audit, MaintenanceScheduler maintenance,
                           RepositoryProperties properties) {
        this.settings = settings;
        this.root = root;
        this.audit = audit;
        this.maintenance = maintenance;
        this.operatorTenant = properties.getOperatorTenant().isBlank()
                ? properties.getDefaultTenant()
                : properties.getOperatorTenant();
    }

    @GetMapping("/walks")
    public String walks(Model model) throws IOException {
        Map<String, TaskSchedule.TaskRun> runs = maintenance.taskRuns();
        model.addAttribute("overview", WalkRuns.overview(key -> settings.getOrDefault(key, null), root,
                name -> lastRun(runs.get(name)), Instant.now()));
        model.addAttribute("defaultDocument", WalkSchedules.DEFAULT);
        return "walks-screen/list";
    }

    /** Save one entry: added when no entry carries the name, replaced otherwise. */
    @PostMapping("/walks/save")
    public String save(@RequestParam("name") String name,
                       @RequestParam("cron") String cron,
                       @RequestParam(value = "enabled", required = false) String enabled,
                       @RequestParam(value = "consumers", required = false) List<String> consumers,
                       RedirectAttributes redirect) throws IOException {
        try {
            String document = WalkRuns.upsert(settings.getOrDefault(WalkSchedules.SETTING, null), name.trim(),
                    cron.trim(), enabled != null, consumers == null ? List.of() : consumers);
            settings.set(WalkSchedules.SETTING, document);
            redirect.addFlashAttribute("message", "Saved the walk '" + name.trim() + "'. It runs at " + cron.trim()
                    + (enabled == null ? ", switched off." : "."));
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", "Nothing saved: " + refused.getMessage());
        }
        return "redirect:/walks";
    }

    /** Remove one entry; the consumers it carried ride no walk until another entry names them. */
    @PostMapping("/walks/remove")
    public String remove(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        settings.set(WalkSchedules.SETTING, WalkRuns.remove(settings.getOrDefault(WalkSchedules.SETTING, null),
                name.trim()));
        redirect.addFlashAttribute("message", "Removed the walk '" + name.trim() + "'.");
        return "redirect:/walks";
    }

    /** Ask for a walk of the store now, on the signed-in operator's behalf. */
    @PostMapping("/walks/run")
    public String run(Principal principal, RedirectAttributes redirect) throws IOException {
        WalkRuns.request(root, audit, operatorTenant, principal == null ? "anonymous" : principal.getName());
        redirect.addFlashAttribute("message", "A walk of the store is requested; every node picks it up within "
                + "half a minute and the rebuild entry's consumers ride it.");
        return "redirect:/walks";
    }

    private static Optional<WalkRuns.LastRun> lastRun(TaskSchedule.TaskRun run) {
        if (run == null || run.finished() == null) {
            return Optional.empty();
        }
        return Optional.of(new WalkRuns.LastRun(run.finished(), run.duration(), run.failed(), run.reads(),
                run.writes()));
    }
}
