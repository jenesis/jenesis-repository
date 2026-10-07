package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.compliance.scan.SignalStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.ConsoleScreen;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The signal-sources screen: what each refreshable signal source holds, read from the record the signal-refresh pass
 * keeps ({@link SignalStatus}) - the same one point read {@code GET /api/admin/signals} answers from, so the screen
 * never waits on a vendor and stands when every one is down.
 */
@Controller
@ConsoleScreen
public class SignalsScreenController {

    private final ArtifactStore root;

    public SignalsScreenController(ArtifactStore root) {
        this.root = root;
    }

    @GetMapping("/ui/signals")
    public String signals(Model model) throws IOException {
        model.addAttribute("signals", SignalsController.SignalsView.of(SignalStatus.read(SignalStatus.space(root))));
        return "compliance/signals";
    }
}
