package build.jenesis.repository.ui;

import build.jenesis.repository.observation.ObservabilityReport;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import module java.base;

/**
 * The metrics overview: every metric, health state and background-task status this deployment reports, each with the
 * description from its registration.
 *
 * <p>A plain page with no graphs, for an operator who is not on a dashboard - the console-side companion to the
 * observability admin API and the Actuator read, off the same collected report, so the three cannot disagree.
 * Read-only and searchable; a disabled or absent source contributes nothing and so is not listed, and an empty
 * report degrades to a friendly empty state rather than an empty table.
 */
@Controller
public class ObservabilityScreenController {

    @GetMapping("/observability")
    public String observability(Model model) throws IOException {
        model.addAttribute("report", ObservabilityReport.discover());
        return "console/observability";
    }
}
