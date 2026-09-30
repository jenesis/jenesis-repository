package build.jenesis.repository.search.web;

import java.io.IOException;
import java.util.List;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static build.jenesis.repository.search.web.SearchConsoleConfig.QUALIFIER;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The licence inventory a repository's published coordinates add up to, as its full-text index counted them.
 *
 * <p>It reads the index and nothing else: while the repository's full-text search is off, or its index not built,
 * the inventory reports itself unindexed and empty rather than counting the store on the request path, and the
 * screen says how to switch the index on. Whether it is on is the repository's {@code full-text-search} setting, read
 * from the repository's, the tenant's and the deployment's settings documents - one object per module under a
 * constant prefix.
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
        model.addAttribute("inventory", browse.licenses(repo, settings.repositoryConfig(tenant.name(), repo)));
        return QUALIFIER + "/licenses";
    }
}
