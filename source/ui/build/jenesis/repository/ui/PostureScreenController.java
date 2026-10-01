package build.jenesis.repository.ui;

import module java.base;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The security-posture screen: every potentially unsafe configuration the deployment reports, with why, a safer
 * alternative and the {@code jenrepo.*} setting that fixes it. Read-only, and no advisory prints a secret. The
 * configuration comes from {@link PostureSource} and the tenant from {@link CurrentTenant}.
 */
@Controller
@ConsoleScreen
public class PostureScreenController {

    private final PostureSource source;

    private final CurrentTenant current;

    public PostureScreenController(PostureSource source, CurrentTenant current) {
        this.source = source;
        this.current = current;
    }

    @GetMapping("/ui/posture")
    public String posture(Model model) throws IOException {
        PostureSource.Collected collected = source.collect(current.name());
        model.addAttribute("posture", collected.report());
        model.addAttribute("postureCollectedAt", collected.collectedAt());
        return "console/posture";
    }
}
