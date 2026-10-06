package build.jenesis.repository.dependents.web;

import java.io.IOException;
import java.util.List;

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
 * Who depends on a coordinate, read from the durable index the scheduled sweep builds.
 *
 * <p>No rebuild on the read path - that is the sweep's job - so the screen shows how fresh the index is and
 * reads a null stamp as "not yet built". A deployment without this module has no such screen at all.
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
                             @RequestParam(name = "coordinate", defaultValue = "") String coordinate,
                             @RequestParam(name = "package", defaultValue = "") String dependency,
                             @RequestParam(name = "version", defaultValue = "") String version,
                             Model model) throws IOException {
        model.addAttribute("repo", repo);
        model.addAttribute("available", dependentsReview.dependentsAvailable());
        model.addAttribute("coordinate", coordinate);
        model.addAttribute("dependency", dependency);
        model.addAttribute("version", version);
        if (dependentsReview.dependentsAvailable()) {
            model.addAttribute("coordinates", dependentsReview.dependencyCoordinates(repo));
            model.addAttribute("dependents",
                    coordinate.isBlank() ? List.of() : dependentsReview.dependents(repo, coordinate));
            // The panel renders the durable index only (no rebuild on the read path - that is the
            // scheduled sweep's job), so it shows how fresh that index is; null reads as "not yet built".
            model.addAttribute("lastBuilt", dependentsReview.dependentsBuiltAt(repo));
            // The declared tier: what manifests state, listed apart from the resolved dependents above because a
            // requirement is not a version anything was built against.
            model.addAttribute("declared", dependency.isBlank() ? null
                    : dependentsReview.declarations(repo, dependency, version));
            model.addAttribute("declaredBuilt", dependentsReview.declarationsBuiltAt(repo));
        } else {
            model.addAttribute("coordinates", List.of());
            model.addAttribute("dependents", List.of());
            model.addAttribute("lastBuilt", null);
            model.addAttribute("declared", null);
            model.addAttribute("declaredBuilt", null);
        }
        return QUALIFIER + "/dependents";
    }
}
