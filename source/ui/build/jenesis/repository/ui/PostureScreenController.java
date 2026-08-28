package build.jenesis.repository.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import module java.base;

/**
 * The security-posture screen: every potentially-unsafe configuration this deployment reports about itself, each
 * with why it is unsafe, a safer alternative and the exact {@code jenreg.*} setting that fixes it.
 *
 * <p>Read-only - observing posture never changes it - and no advisory prints a secret value. Where the effective
 * configuration comes from is the {@link PostureSource} seam's business, and which tenant is being asked about is
 * {@link CurrentTenant}'s, so this screen is the same screen on a deployment with one tenant and on one with many.
 */
@Controller
public class PostureScreenController {

    private final PostureSource source;

    private final CurrentTenant current;

    public PostureScreenController(PostureSource source, CurrentTenant current) {
        this.source = source;
        this.current = current;
    }

    @GetMapping("/posture")
    public String posture(Model model) throws IOException {
        PostureSource.Collected collected = source.collect(current.name());
        model.addAttribute("posture", collected.report());
        // The screen states when the snapshot was taken: a posture read presented without a time reads as current.
        model.addAttribute("postureCollectedAt", collected.collectedAt());
        return "console/posture";
    }
}
