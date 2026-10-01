package build.jenesis.repository.search.web;

import java.io.IOException;

import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static build.jenesis.repository.search.web.SearchConsoleConfig.QUALIFIER;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The licence inventory of a repository as the last count left it - the stored report {@code GET /api/licenses} answers
 * from, read through {@link RepositoryBrowse#licenses}.
 *
 * <p>The screen never counts: it renders what is stored with its as-of time, offers an editor the button that starts a
 * count in the background, and while one runs renders the shared running marker, which keeps the page polling. Rows
 * link into the browse search where the repository's {@code full-text-search} setting is on, since only that drill-down
 * needs the index.
 */
@Controller
@ConsoleScreen
public class LicenceInventoryScreenController {

    private final RepositoryBrowse browse;
    private final SettingsAdmin settings;
    private final CurrentTenant tenant;

    public LicenceInventoryScreenController(RepositoryBrowse browse, SettingsAdmin settings, CurrentTenant tenant) {
        this.browse = browse;
        this.settings = settings;
        this.tenant = tenant;
    }

    @GetMapping("/ui/repositories/{repo}/licenses")
    public String licenses(@PathVariable("repo") String repo, Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("inventory", browse.licenses(repo));
        model.addAttribute("drillDown",
                SearchMode.of(settings.repositoryConfig(tenant.name(), repo)) == SearchMode.FULL_TEXT);
        return QUALIFIER + "/licenses";
    }

    /** The count button: starts the count and lands back on the screen, which shows it running. */
    @PostMapping("/ui/repositories/{repo}/licenses")
    public String count(@PathVariable("repo") String repo, RedirectAttributes redirect) throws IOException {
        redirect.addFlashAttribute("message", browse.countLicenses(repo)
                ? "License count started; this screen shows its result when it finishes."
                : "A license count is already running; this screen shows its result when it finishes.");
        return "redirect:/ui/repositories/" + repo + "/licenses";
    }
}
