package build.jenesis.repository.demo.web;

import module java.base;

import build.jenesis.repository.ui.ConsoleScreen;
import build.jenesis.repository.ui.CurrentTenant;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The demo's page and the route that starts it, under the first-run guide's {@code /ui/setup} and so a super-admin's.
 *
 * <p>The start answers at once: it checks the typed phrase, starts the run on a thread of its own and sends the
 * operator to the page, which renders what the run has stored so far and refreshes itself while it is running. The
 * page never waits on the run, and a run that stopped part way reads as stopped rather than as running forever.
 */
@Controller
@ConsoleScreen
public class DemoController {

    /** The page, and where the guide's offer posts. */
    public static final String ROUTE = "/ui/setup/demo";

    private final DemoRun run;
    private final CurrentTenant tenant;

    public DemoController(DemoRun run, CurrentTenant tenant) {
        this.run = run;
        this.tenant = tenant;
    }

    /** What the tenant's demo run has done so far, or that none has run. */
    @GetMapping(ROUTE)
    public String page(Model model) throws IOException {
        String selected = tenant.name();
        Optional<DemoRun.State> state = selected == null ? Optional.empty() : run.state(selected);
        model.addAttribute("tenant", selected);
        model.addAttribute("run", state.orElse(null));
        model.addAttribute("summary", state.map(DemoController::summary).orElse(""));
        model.addAttribute("offered", selected != null && state.isEmpty() && !run.holdsRepository(selected));
        return "first-run-demo/run";
    }

    /**
     * Start the demo for the selected tenant, confirmed by the typed phrase the guide's dialog submits as
     * {@code confirm}; a request without it, or for a tenant holding a repository, starts nothing and says why.
     */
    @PostMapping(ROUTE)
    public String start(@RequestParam(name = "confirm", defaultValue = "") String confirm,
                        Authentication authentication, RedirectAttributes redirect) throws IOException {
        String selected = tenant.name();
        if (selected == null) {
            redirect.addFlashAttribute("error", "Choose a tenant first; the demo fills the tenant the console has "
                    + "selected.");
            return "redirect:/ui/tenants";
        }
        if (!DemoRun.PHRASE.equals(confirm.trim())) {
            redirect.addFlashAttribute("error", "Nothing was loaded: type \"" + DemoRun.PHRASE + "\" to confirm the "
                    + "demo.");
            return "redirect:/ui/setup";
        }
        DemoRun.Started started = run.start(selected, authentication == null ? "console" : authentication.getName());
        if (!started.started()) {
            redirect.addFlashAttribute("error", "Nothing was loaded: " + started.reason());
            return "redirect:" + ROUTE;
        }
        redirect.addFlashAttribute("message", "The demo is loading into " + selected + ".");
        return "redirect:" + ROUTE;
    }

    /** One sentence summing a run up by outcome. */
    static String summary(DemoRun.State state) {
        List<String> said = new ArrayList<>();
        said.add(count(state.count(DemoRun.Kind.REPOSITORY, DemoRun.Outcome.DONE), "repository", "repositories")
                + " created");
        said.add(count(state.count(DemoRun.Kind.PUBLISH, DemoRun.Outcome.DONE), "file", "files") + " published");
        long held = state.count(DemoRun.Kind.PUBLISH, DemoRun.Outcome.HELD);
        if (held > 0) {
            said.add(count(held, "file", "files") + " held for review");
        }
        said.add(count(state.count(DemoRun.Kind.FETCH, DemoRun.Outcome.DONE), "file", "files")
                + " read through the proxies");
        said.add(count(state.count(DemoRun.Kind.SETTING, DemoRun.Outcome.DONE), "settings change", "settings changes")
                + " made");
        long failed = state.count(DemoRun.Outcome.FAILED) + state.count(DemoRun.Outcome.REFUSED);
        if (failed > 0) {
            said.add(count(failed, "step", "steps") + " did not go through");
        }
        return String.join(", ", said) + ".";
    }

    private static String count(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
