package build.jenesis.repository.format.lifecycle.console;

import module java.base;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.lifecycle.web.LifecycleMarks;
import build.jenesis.repository.ui.ConsoleScreen;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.ConsoleActor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A repository's deprecated and yanked versions, and the form that marks or clears one.
 *
 * <p>Everything goes through {@link LifecycleMarks}, the service {@code /api/lifecycle} answers from, so a repository
 * the API refuses a mark for is refused here in the same words, a withheld version's mark is as absent here as from
 * the API, and a change is audited under the same action as the signed-in member. The listing is a page with a link
 * to the next; narrowed to one coordinate it is that coordinate's marks, whole.
 */
@Controller
@ConsoleScreen
public class LifecycleScreenController {

    private final LifecycleMarks marks;
    private final CurrentTenant tenant;
    private final ConsoleActor actor;

    public LifecycleScreenController(LifecycleMarks marks, CurrentTenant tenant, ConsoleActor actor) {
        this.marks = marks;
        this.tenant = tenant;
        this.actor = actor;
    }

    /** The marks - a page of the repository's, or one coordinate's - and the form, filled from {@code coordinate} and
     *  {@code version} when a version's page links here. Such a link names the inventory's coordinate and the
     *  {@code path} the version serves at, and the format says which coordinate its marks name. Narrowed to one
     *  coordinate, the page lists that coordinate's folder of marks - one listing, bounded by the coordinate's own
     *  versions, the read the API's coordinate form makes from the same service. */
    @GetMapping("/ui/repositories/{repo}/lifecycle")
    public String screen(@PathVariable("repo") String repository,
                         @RequestParam(name = "coordinate", defaultValue = "") String named,
                         @RequestParam(name = "version", defaultValue = "") String version,
                         @RequestParam(name = "path", defaultValue = "") String path,
                         @RequestParam(name = "after", defaultValue = "") String after, Model model)
            throws IOException {
        String coordinate = named.isBlank() || path.isBlank() ? named
                : marks.coordinateOf(tenant.name(), repository, named, path);
        model.addAttribute("repository", repository);
        model.addAttribute("coordinate", coordinate);
        model.addAttribute("version", version);
        // The marks this repository's clients see, the only ones offered; a repository of no installed type is
        // offered both, as the API takes either there.
        Map<LifecycleMark, String> shown = marks.shown(tenant.name(), repository);
        Map<LifecycleMark, String> offered = shown.isEmpty() ? LifecycleMark.shown(LifecycleMark.values()) : shown;
        model.addAttribute("states", Stream.of(LifecycleMark.values()).filter(offered::containsKey).toList());
        model.addAttribute("labels", offered);
        model.addAttribute("title", LifecycleConsoleModule.title(offered));
        model.addAttribute("deprecates", offered.containsKey(LifecycleMark.DEPRECATED));
        model.addAttribute("yanks", offered.containsKey(LifecycleMark.YANKED));
        model.addAttribute("yank", LifecycleMark.YANKED);
        model.addAttribute("refusal", marks.refusal(tenant.name(), repository).orElse(null));
        if (coordinate.isBlank()) {
            LifecycleMarks.Page page = marks.page(tenant.name(), repository, after.isBlank() ? null : after, null);
            model.addAttribute("marks", page.marks());
            model.addAttribute("next", page.next());
        } else {
            model.addAttribute("marks", marks.coordinate(tenant.name(), repository, coordinate));
            model.addAttribute("next", null);
        }
        return "lifecycle/marks";
    }

    @PostMapping("/ui/repositories/{repo}/lifecycle")
    public String mark(@PathVariable("repo") String repository,
                       @RequestParam("coordinate") String coordinate,
                       @RequestParam("version") String version,
                       @RequestParam("state") String state,
                       @RequestParam(name = "message", defaultValue = "") String message,
                       RedirectAttributes redirect) throws IOException {
        Optional<LifecycleMark> parsed = LifecycleMark.parse(state);
        if (parsed.isEmpty()) {
            redirect.addFlashAttribute("error", "Choose a mark.");
            return back(repository, coordinate);
        }
        Optional<String> refused = marks.mark(tenant.name(), repository, coordinate.trim(), version.trim(),
                parsed.get(), message, actor.name());
        if (refused.isPresent()) {
            redirect.addFlashAttribute("error", refused.get());
        } else {
            redirect.addFlashAttribute("message", coordinate.trim() + " " + version.trim() + " is marked "
                    + marks.shown(tenant.name(), repository).getOrDefault(parsed.get(), parsed.get().word()) + ".");
        }
        return back(repository, coordinate.trim());
    }

    @PostMapping("/ui/repositories/{repo}/lifecycle/clear")
    public String clear(@PathVariable("repo") String repository,
                        @RequestParam("coordinate") String coordinate,
                        @RequestParam("version") String version,
                        @RequestParam(name = "narrowed", defaultValue = "false") boolean narrowed,
                        RedirectAttributes redirect) throws IOException {
        boolean cleared = marks.clear(tenant.name(), repository, coordinate, version, actor.name());
        redirect.addFlashAttribute("message", cleared ? coordinate + " " + version + " is no longer marked."
                : coordinate + " " + version + " was not marked.");
        return back(repository, narrowed ? coordinate : "");
    }

    /** Back to the page, narrowed to {@code coordinate} when one was being looked at. */
    private static String back(String repository, String coordinate) {
        return "redirect:/ui/repositories/" + repository + "/lifecycle" + (coordinate.isBlank() ? ""
                : "?coordinate=" + URLEncoder.encode(coordinate, StandardCharsets.UTF_8));
    }
}
