package build.jenesis.repository.dependents.web;

import java.io.IOException;

import build.jenesis.repository.ui.ConsoleScreen;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import static build.jenesis.repository.dependents.web.DependentsConsoleConfig.QUALIFIER;

/**
 * What depends on a package this repository holds: its resolved dependents, given a version, and its declared ones,
 * each a page resumed by its own cursor - the answer {@code /api/repository/dependents} gives. A version's page links
 * here.
 *
 * <p>Nothing is indexed on the read path - that is the scheduled passes' job - so the screen shows how fresh the
 * declared index is and reads a never-built one as exactly that. A deployment without this module has no such screen.
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
                             @RequestParam(name = "ecosystem", defaultValue = "") String ecosystem,
                             @RequestParam(name = "coordinate", defaultValue = "") String coordinate,
                             @RequestParam(name = "version", defaultValue = "") String version,
                             @RequestParam(name = "after", defaultValue = "") String after,
                             @RequestParam(name = "declaredAfter", defaultValue = "") String declaredAfter,
                             Model model) throws IOException {
        if (after.contains("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a cursor: " + after);
        }
        model.addAttribute("repo", repo);
        model.addAttribute("ecosystem", ecosystem);
        model.addAttribute("coordinate", coordinate);
        model.addAttribute("version", version);
        model.addAttribute("after", after);
        model.addAttribute("declaredAfter", declaredAfter);
        model.addAttribute("view", ecosystem.isBlank() || coordinate.isBlank() ? null
                : dependentsReview.dependents(repo, ecosystem, coordinate, version, after, declaredAfter));
        return QUALIFIER + "/dependents";
    }
}
