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
 * The first boot's wizard ({@link SetupWizard}): the starter credential's step, then the decisions a new deployment
 * should make - one step per group of its essential settings - then the review, and on completion every changed value
 * saved at once. Super-admin only, under {@code /setup/**} in the security matrix.
 *
 * <p>It never blocks and is always escapable: every step renders from what is stored, nothing is written until the
 * review's completion, a skip is one click and remembered for the session, and the wizard is reachable again as
 * First-run setup, under Settings, whenever it is wanted - a first-run screen an operator cannot leave is worse than
 * no first-run screen. The starter credential's step reads the deployment's environment (whether the console's admin
 * key and the API's bootstrap key are set, and whether this very session is on the starter key), since those are
 * secrets a deployment is provisioned with rather than values in the store.
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

    /** The wizard's first step, starting from what the deployment holds. Its reads are the settings documents - one
     *  object per module under a constant prefix, the same read the settings screen makes - and nothing that grows
     *  with the store. */
    @GetMapping(SetupWizard.ROUTE)
    public String setup(Authentication authentication, Model model) throws IOException {
        model.addAttribute("wizard", WizardFlow.start(wizard.definition(starter(authentication)), wizard.held()));
        return "wizard";
    }

    /** A step posted: moved on, back or completed ({@link WizardFlow#apply}). A completed run saves what it changed
     *  and lands where the console would have, the guide done for this session. Like the first step it reads the
     *  settings documents, one object per module under a constant prefix, and nothing that grows with the store. */
    @PostMapping(SetupWizard.ROUTE)
    public String step(@RequestParam Map<String, String> form, Authentication authentication, HttpSession session,
                       Model model, RedirectAttributes redirect) throws IOException {
        WizardFlow flow = WizardFlow.resume(wizard.definition(starter(authentication)), form);
        if (flow.apply(form.get(WizardFlow.ACTION))) {
            int changed = wizard.complete(flow);
            SetupWizard.skip(session);
            redirect.addFlashAttribute("message", changed == 0 ? "Setup applied; nothing needed changing."
                    : "Setup applied: " + changed + (changed == 1 ? " setting" : " settings") + " saved.");
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
