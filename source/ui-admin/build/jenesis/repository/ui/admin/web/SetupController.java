package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.ui.identity.StarterCredential;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The first-run setup screen: the decisions a new deployment should make, walked in order, each rendered from the
 * settings catalogue ({@link SetupWizard}). Super-admin only, under {@code /setup/**} in the security matrix.
 *
 * <p>It never blocks and is always escapable: it renders the current state, every save posts to the settings
 * screen's own save route and comes back here, a skip is one click and remembered for the session, and the screen
 * is reachable again from the Administration menu whenever it is wanted - a first-run screen an operator cannot
 * leave is worse than no first-run screen. The starter-credential step is rendered from the deployment's
 * environment (whether the console's admin key and the API's bootstrap key are set, and whether this very session
 * is on the starter key), since those are secrets a deployment is provisioned with rather than dials in the store.
 */
@Controller
public class SetupController {

    private final SetupWizard wizard;
    private final Environment environment;

    public SetupController(SetupWizard wizard, Environment environment) {
        this.wizard = wizard;
        this.environment = environment;
    }

    /** The guide. Its reads are the settings document - one object per module under a constant prefix, narrow by
     *  construction and the same read the settings screen makes - and nothing that grows with the store. */
    @GetMapping("/setup")
    public String setup(Authentication authentication, Model model) throws IOException {
        model.addAttribute("steps", wizard.steps());
        model.addAttribute("starterSession", StarterCredential.signedInWith(authentication));
        model.addAttribute("adminKeySet", set("jenreg.ui.admin-key"));
        model.addAttribute("bootstrapKeySet", set("jenreg.bootstrap-key"));
        model.addAttribute("wizardOn", wizard.on());
        return "setup";
    }

    /** Skip the guide for this session and land where the console would have: the instances screen. */
    @PostMapping("/setup/skip")
    public String skip(HttpSession session) {
        SetupWizard.skip(session);
        return "redirect:/instances";
    }

    private boolean set(String key) {
        return !environment.getProperty(key, "").isBlank();
    }
}
