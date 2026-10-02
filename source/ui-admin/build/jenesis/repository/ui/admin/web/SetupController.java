package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.ui.AdministratorClaim;
import build.jenesis.repository.ui.OAuth2PrincipalService;
import build.jenesis.repository.ui.admin.ConsoleSettingsContributor;
import build.jenesis.repository.ui.identity.StarterCredential;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
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

    /** The environment variable the settings master key is provisioned in. */
    private static final String SECRETS_KEY = "JENREPO_SECRETS_KEY";

    private final SetupWizard wizard;
    private final Environment environment;
    private final SettingsAdmin settings;
    /** Present exactly while the OAuth2 sign-in module is installed in this console. */
    private final ObjectProvider<OAuth2PrincipalService> signIn;

    public SetupController(SetupWizard wizard, Environment environment, SettingsAdmin settings,
                           ObjectProvider<OAuth2PrincipalService> signIn) {
        this.wizard = wizard;
        this.environment = environment;
        this.settings = settings;
        this.signIn = signIn;
    }

    /** The wizard's first step, starting from what the deployment holds. */
    @GetMapping(SetupWizard.ROUTE)
    public String setup(Authentication authentication, Model model) throws IOException {
        model.addAttribute("wizard", WizardFlow.start(wizard.definition(starter(authentication)), wizard.held(),
                wizard.suggested()));
        model.addAttribute("github", github());
        return "wizard";
    }

    /**
     * What the starter step offers for signing in with GitHub and being made administrator: whether GitHub sign-in is
     * installed at all, whether an app is configured already and from where, whether a client secret can be stored
     * (it is sealed with the settings master key), the callback address to register the app with and, where no master
     * key is provisioned, a freshly generated one to provision.
     */
    public record GithubSetup(boolean installed, boolean configured, boolean pinned, boolean canStoreSecret,
                              String callback, String suggestedKey) {

        /** Where GitHub registers a new OAuth app. */
        public String newApp() {
            return "https://github.com/settings/applications/new";
        }
    }

    private GithubSetup github() {
        boolean installed = signIn.getIfAvailable() != null;
        boolean pinned = !environment.getProperty("jenrepo." + ConsoleSettingsContributor.GITHUB_CLIENT_ID, "")
                .isBlank();
        boolean configured = !settings.effective(ConsoleSettingsContributor.GITHUB_CLIENT_ID, "").isBlank();
        boolean canStoreSecret = !environment.getProperty(SECRETS_KEY, "").isBlank();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new GithubSetup(installed, configured, pinned, canStoreSecret,
                ServletUriComponentsBuilder.fromCurrentContextPath().path("/login/oauth2/code/github").toUriString(),
                canStoreSecret ? null : "k1:" + Base64.getEncoder().encodeToString(key));
    }

    /**
     * Save the GitHub OAuth app pasted on the starter step - unless the environment fixes it - and sign in with it,
     * claiming administration for the identity GitHub returns ({@link AdministratorClaim}). The claim is this
     * super-admin session's alone, good once and for minutes, so only the operator who started the guide can make
     * someone administrator through it.
     *
     * <p>Like every step of the guide it reads the deployment's settings, which list the settings space: one document
     * per contributing module, narrow by construction whatever the repositories hold.
     */
    @PostMapping(SetupWizard.ROUTE + "/github")
    public String github(@RequestParam(name = "clientId", defaultValue = "") String clientId,
                         @RequestParam(name = "clientSecret", defaultValue = "") String clientSecret,
                         HttpSession session, RedirectAttributes redirect) throws IOException {
        GithubSetup github = github();
        if (!github.installed()) {
            redirect.addFlashAttribute("error", "GitHub sign-in is not installed in this console.");
            return "redirect:" + SetupWizard.ROUTE;
        }
        if (!github.pinned()) {
            if (clientId.isBlank() || clientSecret.isBlank()) {
                redirect.addFlashAttribute("error", "Paste both the client id and the client secret of the app.");
                return "redirect:" + SetupWizard.ROUTE;
            }
            try {
                settings.saveAll(Map.of(ConsoleSettingsContributor.GITHUB_CLIENT_ID, clientId.trim(),
                        ConsoleSettingsContributor.GITHUB_CLIENT_SECRET, clientSecret.trim()));
            } catch (IllegalArgumentException | IllegalStateException refused) {
                redirect.addFlashAttribute("error", refused.getMessage());
                return "redirect:" + SetupWizard.ROUTE;
            }
        }
        AdministratorClaim.offer(session, Instant.now());
        return "redirect:/oauth2/authorization/github";
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
        model.addAttribute("github", github());
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
