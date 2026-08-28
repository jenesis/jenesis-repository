package build.jenesis.repository.ui;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import module java.base;

/**
 * The security-posture screen: every potentially-unsafe configuration this deployment reports about itself, each
 * with why it is unsafe, a safer alternative and the exact {@code jenreg.*} setting that fixes it.
 *
 * <p>The report is collected once through {@link PostureReport#discover}, the same source every other posture
 * surface reads, so two of them can never name different numbers. Observing posture never changes it, and no
 * advisory prints a secret value.
 */
@Controller
public class PostureScreenController {

    private final Environment environment;

    public PostureScreenController(Environment environment) {
        this.environment = environment;
    }

    /**
     * What the screen renders. The counts are taken here rather than in the template because they are a question
     * for the report - the template's job is to lay out an answer, not to ask one.
     */
    public record View(long critical, long warn, long info, List<SecurityAdvisory> advisories) {

        public static View of(PostureReport report) {
            return new View(report.count(Severity.CRITICAL), report.count(Severity.WARN),
                    report.count(Severity.INFO), report.scoped(Scope.DEPLOYMENT));
        }
    }

    @GetMapping("/posture")
    public String posture(Model model) {
        model.addAttribute("posture", View.of(PostureReport.discover(Configuration.of(environment::getProperty))));
        return "console/posture";
    }
}
