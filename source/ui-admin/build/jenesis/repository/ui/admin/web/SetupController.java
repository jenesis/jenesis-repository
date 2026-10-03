package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.observation.Contributions;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.SetupOffer;
import build.jenesis.repository.ui.identity.StarterCredential;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p>The first page also carries what installed modules offer an operator setting up ({@link SetupOffer}) - signing
 * in with GitHub and becoming administrator, a demo for an empty deployment - each posting to a route of its own
 * module, so the wizard's own steps are untouched.
 */
@Controller
@ConsoleScreen
public class SetupController {

    private final SetupWizard wizard;
    private final Environment environment;
    private final ObjectProvider<SetupOffer> offers;
    private final CurrentTenant tenant;

    public SetupController(SetupWizard wizard, Environment environment, ObjectProvider<SetupOffer> offers,
                           CurrentTenant tenant) {
        this.wizard = wizard;
        this.environment = environment;
        this.offers = offers;
        this.tenant = tenant;
    }

    /** The wizard's first step, starting from what the deployment holds. */
    @GetMapping(SetupWizard.ROUTE)
    public String setup(Authentication authentication, Model model) throws IOException {
        model.addAttribute("wizard", WizardFlow.start(wizard.definition(starter(authentication)), wizard.held(),
                wizard.suggested()));
        model.addAttribute("offers", offers());
        return "wizard";
    }

    /**
     * What the installed modules offer on the first page, for the selected tenant if there is one, in their order. An
     * offer that fails is shown as failed, and logged.
     */
    private List<SetupOffer.Offer> offers() {
        SetupOffer.Viewer viewer = new SetupOffer.Viewer(Optional.ofNullable(tenant.name()));
        return Contributions.collect("first-run offer", Contributions.ordered(offers.stream(), SetupOffer::order),
                offer -> offer.offer(viewer),
                (offer, failure) -> Optional.of(SetupOffer.Offer.failed(offer.getClass().getSimpleName(),
                        Contributions.reason(failure))))
                .stream().flatMap(Optional::stream).toList();
    }

    /** A step posted ({@link WizardFlow#apply}); a completed run saves what it changed and lands where the console
     *  would have. */
    @PostMapping(SetupWizard.ROUTE)
    public String step(@RequestParam Map<String, String> form, Authentication authentication, HttpSession session,
                       Model model, RedirectAttributes redirect) throws IOException {
        WizardFlow flow = WizardFlow.resume(wizard.definition(starter(authentication)), form);
        if (flow.apply(form.get(WizardFlow.ACTION))) {
            SetupWizard.Applied applied = wizard.complete(flow, authentication.getName());
            SetupWizard.skip(session);
            int changed = applied.changed();
            redirect.addFlashAttribute("message", (changed == 0 ? "Setup applied; no setting needed changing."
                    : "Setup applied: " + changed + (changed == 1 ? " setting" : " settings") + " saved.")
                    + (applied.administrator().isBlank() ? ""
                            : " " + applied.administrator() + " administers the deployment.")
                    + " Next, give each build its own credential under Access, New credential.");
            if (!applied.key().isBlank()) {
                // Shown this once: only its hash is stored.
                redirect.addFlashAttribute("issuedKey", applied.key());
                redirect.addFlashAttribute("issuedFor", applied.administrator());
            }
            return "redirect:/ui/";
        }
        model.addAttribute("wizard", flow);
        model.addAttribute("offers", offers());
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
