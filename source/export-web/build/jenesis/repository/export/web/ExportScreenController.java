package build.jenesis.repository.export.web;

import module java.base;

import build.jenesis.repository.export.Exports;
import build.jenesis.repository.ui.CurrentTenant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Publish a whole repository to another one from the console, and watch the jobs doing it.
 *
 * <p>The screen names a repository of the session's tenant and the URL the other repository's client would be pointed
 * at, and starts a job through {@link Exports} - the service the API answers from - so a refusal reads the same here
 * as it does to a script. It never waits on a job: a running one is shown as running and the page refreshes itself,
 * and each job's counts read back one stored document at a time, a page of them at once.
 */
@Controller
public class ExportScreenController {

    private final Exports exports;
    private final CurrentTenant tenant;

    public ExportScreenController(Exports exports, CurrentTenant tenant) {
        this.exports = exports;
        this.tenant = tenant;
    }

    @GetMapping("/ui/export")
    public String screen(@RequestParam(name = "repository", defaultValue = "") String repository,
                         @RequestParam(name = "after", defaultValue = "") String after, Model model)
            throws IOException {
        model.addAttribute("tenant", tenant.name());
        model.addAttribute("repository", repository);
        if (repository.isBlank()) {
            model.addAttribute("jobs", List.of());
            model.addAttribute("next", null);
            model.addAttribute("running", false);
            return "export/form";
        }
        Exports.JobPage page = exports.jobs(tenant.name(), repository, after.isBlank() ? null : after);
        model.addAttribute("jobs", page.jobs());
        model.addAttribute("next", page.next().orElse(null));
        model.addAttribute("running", page.jobs().stream().anyMatch(Exports.Job::running));
        return "export/form";
    }

    @PostMapping("/ui/export")
    public String start(@RequestParam("repository") String repository,
                        @RequestParam("url") String url,
                        @RequestParam(name = "username", defaultValue = "") String username,
                        @RequestParam(name = "password", defaultValue = "") String password,
                        @RequestParam(name = "token", defaultValue = "") String token,
                        @RequestParam(name = "resume", defaultValue = "") String resume,
                        RedirectAttributes redirect) throws IOException {
        Exports.Started started = exports.start(tenant.name(), repository, url,
                Exports.credential(username, password, token), resume.isBlank() ? null : resume);
        if (started.accepted()) {
            redirect.addFlashAttribute("message", (resume.isBlank() ? "Started export " : "Resumed export ")
                    + started.job() + " of " + repository + " to " + url + ".");
        } else {
            redirect.addFlashAttribute("error", "Nothing exported: " + started.reason());
        }
        redirect.addAttribute("repository", repository);
        return "redirect:/ui/export";
    }
}
