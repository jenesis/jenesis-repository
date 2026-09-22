package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.store.CacheService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Triggers eviction directly on the shared volume (no call into jenesis-cache): enforce the size
 * cap now, expire stale (ttl) entries now, or clear a project entirely. All routes are POST and are
 * admin-grade - clearing a project wipes its cache for everyone - so {@code AdminSecurityConfig} gates the
 * eviction routes under a project's {@code evict} path on admin in the selected tenant, above the generic
 * editor mutation gate.
 */
@Controller
public class EvictionController {

    private final CacheService service;

    public EvictionController(CacheService service) {
        this.service = service;
    }

    @PostMapping("/projects/{name}/evict/size")
    public String enforceSizeCap(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "size-cap sweep", service.enforceSizeCap(name));
        return "redirect:/projects/" + name;
    }

    @PostMapping("/projects/{name}/evict/ttl")
    public String expireTtl(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "stale-entry sweep", service.expireTtl(name));
        return "redirect:/projects/" + name;
    }

    @PostMapping("/projects/{name}/evict/clear")
    public String clear(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "clear", service.clearAll(name));
        return "redirect:/projects/" + name;
    }

    @PostMapping("/projects/{name}/recount")
    public String recount(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        flash(redirect, "count", service.recount(name));
        return "redirect:/projects/" + name;
    }

    /** Every pass walks the project's entries, so it runs in the background and the page shows its outcome. */
    private static void flash(RedirectAttributes redirect, String action, boolean started) {
        redirect.addFlashAttribute("message", started
                ? "Started the " + action + " in the background; this page shows the outcome when it lands."
                : "A pass is already running on this project.");
    }
}
