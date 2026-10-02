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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The first boot's wizard ({@link SetupWizard}), super-admin only. Always escapable: nothing is written until the
 * review's completion, a skip is remembered for the session, and the wizard is reachable again under Settings. The
 * starter credential's step reads the environment, where those secrets are provisioned. Every step reads only the
 * settings documents.
 */
@Controller
@ConsoleScreen
public class SetupController {

    private final SetupWizard wizard;
    private final Environment environment;

    public SetupController(SetupWizard wizard, Environment environment) {
        this.wizard = wizard;
        this.environment = environment;
    }

    /** The wizard's first step, starting from what the deployment holds. */
    @GetMapping(SetupWizard.ROUTE)
    public String setup(Authentication authentication, Model model) throws IOException {
        model.addAttribute("wizard", WizardFlow.start(wizard.definition(starter(authentication)), wizard.held()));
        return "wizard";
    }

    /** A step posted ({@link WizardFlow#apply}); a completed run saves what it changed and lands where the console
     *  would have. */
    @PostMapping(SetupWizard.ROUTE)
    public String step(@RequestParam Map<String, String> form, Authentication authentication, HttpSession session,
                       Model model, RedirectAttributes redirect) throws IOException {
        WizardFlow flow = WizardFlow.resume(wizard.definition(starter(authentication)), form);
        if (flow.apply(form.get(WizardFlow.ACTION))) {
            int changed = wizard.complete(flow);
            SetupWizard.skip(session);
            String administrator = flow.identity().getOrDefault(SetupWizard.ADMINISTRATOR, "");
            redirect.addFlashAttribute("message", (changed == 0 ? "Setup applied; no setting needed changing."
                    : "Setup applied: " + changed + (changed == 1 ? " setting" : " settings") + " saved.")
                    + (administrator.isBlank() ? "" : " " + administrator + " administers the deployment.")
                    + " Next, give each build its own credential under Access, New credential.");
            return "redirect:/ui/";
        }
        model.addAttribute("wizard", flow);
        return "wizard";
    }

    /** Skip the guide for this session and land where the console would have: the tenants screen. */
    @PostMapping(SetupWizard.ROUTE + "/skip")
    public String skip(HttpSession session) {
        SetupWizard.skip(session);
        return "redirect:/ui/";
    }

    private SetupWizard.Starter starter(Authentication authentication) {
        return new SetupWizard.Starter(StarterCredential.signedInWith(authentication), set("jenrepo.ui.admin-key"),
                set("jenrepo.bootstrap-key"));
    }

    private boolean set(String key) {
        return !environment.getProperty(key, "").isBlank();
    }
}
