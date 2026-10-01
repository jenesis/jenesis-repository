package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.store.CacheService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * Starts eviction on a project: enforce the size cap, expire stale entries, or clear it entirely. Admin-grade POSTs
 * ({@code ConsoleAuthorization}), since clearing a project wipes its cache for everyone.
 */
@Controller
@ConsoleScreen
public class EvictionController {

    private final CacheService service;

    public EvictionController(CacheService service) {
        this.service = service;
    }

    /** Starts enforcing the project's size cap. */
    @PostMapping("/ui/projects/{name}/evict/size")
    public String enforceSizeCap(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "size-cap sweep", service.enforceSizeCap(name));
        return "redirect:/ui/projects/" + name;
    }

    /** Starts expiring the project's stale entries. */
    @PostMapping("/ui/projects/{name}/evict/ttl")
    public String expireTtl(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "stale-entry sweep", service.expireTtl(name));
        return "redirect:/ui/projects/" + name;
    }

    @PostMapping("/ui/projects/{name}/evict/clear")
    public String clear(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "clear", service.clearAll(name));
        return "redirect:/ui/projects/" + name;
    }

    @PostMapping("/ui/projects/{name}/recount")
    public String recount(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "count", service.recount(name));
        return "redirect:/ui/projects/" + name;
    }

    /** Every pass walks the project's entries, so it runs in the background and the page shows its outcome. */
    private static void flash(RedirectAttributes redirect, String action, boolean started) {
        redirect.addFlashAttribute("message", started
                ? "Started the " + action + " in the background; this page shows the outcome when it lands."
                : "A pass is already running on this project.");
    }
}
