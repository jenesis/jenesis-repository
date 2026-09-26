package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.server.spi.NodeCaches;
import build.jenesis.repository.ui.store.CacheClear;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The read caches of the node that served this request, and the clear that drops them. A super-admin's screen under
 * Operations: the caches hold every tenant's credentials, settings and listings, and the clear reaches every node's
 * grants, so nothing about it is one tenant's.
 */
@Controller
public class CachesController {

    private final CacheClear clear;

    public CachesController(CacheClear clear) {
        this.clear = clear;
    }

    @GetMapping("/ui/caches")
    public String caches(Model model) {
        model.addAttribute("node", NodeCaches.node());
        model.addAttribute("caches", NodeCaches.caches());
        return "caches";
    }

    @PostMapping("/ui/caches/clear")
    public String clear(RedirectAttributes redirect) throws IOException {
        NodeCaches.Cleared cleared = clear.clear();
        redirect.addFlashAttribute("message", "Dropped " + cleared.cleared() + " cached entries on " + cleared.node()
                + "." + (cleared.grantsEverywhere() ? " Every node's authorization cache follows within seconds;"
                        : " This deployment enforces no authorization, so there were no grants to drop;")
                + " their other caches keep their own until each entry's ttl.");
        return "redirect:/ui/caches";
    }
}
