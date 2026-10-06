package build.jenesis.repository.dependents.web;

import java.io.IOException;

import build.jenesis.repository.ui.store.TenantScope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static build.jenesis.repository.dependents.web.DependentsConsoleConfig.QUALIFIER;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * Who declares a dependency on a package, read from the durable index the scheduled pass builds; which published
 * versions rely on a given version, as their closures reach it, is that version's page.
 *
 * <p>No index pass on the read path - that is the scheduled pass's job - so the screen shows how fresh the index is and
 * reads a null stamp as "not yet indexed". A deployment without this module has no such screen at all.
 */
@Controller
@ConsoleScreen
public class DependentsScreenController {

    private final DependentsReview dependentsReview;

    public DependentsScreenController(DependentsReview dependentsReview) {
        this.dependentsReview = dependentsReview;
    }

    @GetMapping("/ui/repositories/{repo}/dependents")
    public String dependents(@PathVariable("repo") String repo,
                             @RequestParam(name = "package", defaultValue = "") String dependency,
                             @RequestParam(name = "version", defaultValue = "") String version,
                             Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("available", dependentsReview.dependentsAvailable());
        model.addAttribute("dependency", dependency);
        model.addAttribute("version", version);
        if (dependentsReview.dependentsAvailable()) {
            model.addAttribute("declared", dependency.isBlank() ? null
                    : dependentsReview.declarations(repo, dependency, version));
            model.addAttribute("declaredBuilt", dependentsReview.declarationsBuiltAt(repo));
        } else {
            model.addAttribute("declared", null);
            model.addAttribute("declaredBuilt", null);
        }
        return QUALIFIER + "/dependents";
    }
}
