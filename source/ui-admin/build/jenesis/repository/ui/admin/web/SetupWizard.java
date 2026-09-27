package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.Wizard;
import build.jenesis.repository.ui.identity.StarterCredential;
import build.jenesis.repository.ui.store.SettingsAdmin;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The first boot's wizard: whether a session is sent to it, and what it asks.
 *
 * <p><b>The redirect keys on the starter credential, not on the store.</b> A super-admin whose session is on the
 * starter credential ({@link StarterCredential}) lands on {@code /setup} instead of the tenants screen - once per
 * session, since the screen is skippable and the skip is remembered for the session - for as long as the
 * {@value #SETTING} dial is on. It deliberately does not key on "no runtime configuration stored yet": that state
 * is what the boot log's hardening advice reads, and a dial that also meant it would be a second source of truth for
 * "setup is finished", which is whether the starter credential is still in use.
 *
 * <p><b>The dial is an ordinary setting.</b> {@value #SETTING} is declared in the console's settings contributor
 * with a description, rendered by the settings screen and the generated reference like any other dial, and read
 * here - where the redirect is decided - so switching it off means the redirect never happens rather than the
 * screen rendering an empty shell. On by default ({@link #onByDefault()}, one definition), because the argument
 * for the guide is precisely the operator who does not know to look for it; a deployment provisioned from
 * configuration, rebuilt by CI or started for the hundredth time is the party that can afford to say so, once.
 * There is no {@code jenreg.x=${JENREG_X:default}} line for it anywhere: relaxed binding maps the variable onto
 * the key whether or not a file mentions it, and the default lives here.
 *
 * <p><b>It is the first boot's wizard, derived from the catalogue and never restating it.</b> Its steps are the
 * starter credential's, which is about no setting, then one per group of the deployment's and a tenant's essential
 * settings ({@link Wizard#SETUP}), each row the setting's own description, default and value, then the review; on
 * completion every value changed from what the deployment held is saved in one batch ({@link SettingsAdmin#saveAll}),
 * and nothing is written before that.
 */
@Component
public class SetupWizard {

    /** The dial that switches the first-run redirect off. */
    public static final String SETTING = "setup-wizard";

    /** The one definition of the dial's default: on. A compile-time text constant, so the settings contributor
     *  declares exactly this and the generated reference shows exactly this. */
    public static final String ON_BY_DEFAULT = "true";

    /** The session attribute a skip sets, so a skipped guide does not come back on the next landing of the same
     *  session; a new session on the starter credential is sent there again, which is the point. */
    public static final String SKIPPED = "jenesis.setup.skipped";

    private final SettingsAdmin settings;

    public SetupWizard(SettingsAdmin settings) {
        this.settings = settings;
    }

    /** Whether the guide is on where nothing has said otherwise - the default the code applies. */
    public static boolean onByDefault() {
        return Boolean.parseBoolean(ON_BY_DEFAULT);
    }

    /** Whether the deployment has the guide switched on: the effective value of {@value #SETTING}. */
    public boolean on() throws IOException {
        return Boolean.parseBoolean(settings.effective(SETTING, ON_BY_DEFAULT));
    }

    /**
     * Whether this landing should go to the guide: the session is on the starter credential, it is a super-admin's
     * (the guide writes deployment-wide settings), it has not skipped the guide, and the dial is on.
     */
    public boolean redirects(Authentication authentication, HttpSession session) throws IOException {
        return StarterCredential.signedInWith(authentication)
                && superadmin(authentication)
                && (session == null || session.getAttribute(SKIPPED) == null)
                && on();
    }

    /** Remember that this session chose to skip the guide. */
    public static void skip(HttpSession session) {
        session.setAttribute(SKIPPED, Boolean.TRUE);
    }

    /** What the console knows of the starter credential: whether this session is on it, and whether the console's
     *  admin key and the API's bootstrap key are set - read from the deployment's environment, since they are
     *  secrets it is provisioned with rather than values in the store. */
    public record Starter(boolean session, boolean adminKeySet, boolean bootstrapKeySet) {
    }

    /** The wizard's route: its page, and where each step posts. */
    public static final String ROUTE = "/ui/setup";

    /** The wizard: the starter credential's step, the settings steps, the review, and the checks of its values. */
    public WizardFlow.Definition definition(Starter starter) throws IOException {
        List<WizardFlow.Step> steps = new ArrayList<>();
        for (Wizard.Information information : Wizard.SETUP.information()) {
            List<String> paragraphs = new ArrayList<>(List.of(information.text()));
            if (information.equals(Wizard.STARTER_CREDENTIAL)) {
                paragraphs.add(starter.session() ? "This session is signed in with the starter key: anything it does "
                        + "is recorded against the key, not a person." : "This session is a real identity, not the "
                        + "starter key.");
                paragraphs.add(starter.adminKeySet() ? "The console's admin key (jenreg.ui.admin-key) is set: it grants "
                        + "super-admin over every tenant and is re-provisioned on every boot until the variable is "
                        + "unset." : "The console's admin key (jenreg.ui.admin-key) is not set.");
                paragraphs.add(starter.bootstrapKeySet() ? "The API's bootstrap key (jenreg.bootstrap-key) is set: a "
                        + "non-expiring credential holding every right, re-provisioned on every boot until the variable "
                        + "is unset." : "The API's bootstrap key (jenreg.bootstrap-key) is not set.");
            }
            steps.add(WizardFlow.Step.information(information.title(), paragraphs,
                    List.of(new WizardFlow.Link("Grant a real administrator", "/ui/admin"),
                            new WizardFlow.Link("Issue a real credential", "/ui/credentials"))));
        }
        steps.addAll(WizardFlow.settingsSteps(Wizard.SETUP, views()));
        List<String> review = new ArrayList<>(List.of("Nothing has been saved yet. Applying saves every value changed "
                + "here in one step; a value left at its default is the deployment's until it is set - here again, or "
                + "on the settings screen, whenever it is wanted."));
        if (!on()) {
            review.add("The first-run redirect is switched off for this deployment; this wizard was opened from the "
                    + "menu, and stays there as First-run setup, under Settings.");
        }
        return new WizardFlow.Definition("First-run setup", ROUTE,
                new WizardFlow.Exit("Skip for now", ROUTE + "/skip", true), "Apply setup", "Apply now", steps, review,
                new WizardFlow.Checks() {
                    @Override
                    public Map<String, String> identity(Map<String, String> identity) {
                        return Map.of();
                    }

                    @Override
                    public Map<String, String> settings(Map<String, String> values) throws IOException {
                        return SetupWizard.this.settings.refusals(Setting.Scope.GLOBAL, values, true);
                    }
                });
    }

    /** What the deployment holds already of what the wizard asks - its stored values, which a run starts from. */
    public Map<String, String> held() throws IOException {
        Map<String, String> held = new LinkedHashMap<>();
        views().forEach((key, view) -> {
            if (view.overridden() && !view.pinned() && !view.secret()) {
                held.put(key, view.value());
            }
        });
        return held;
    }

    /**
     * Save what a completed run changed from what the deployment held, in one batch - validated whole, so one refused
     * value saves none. Returns how many values changed.
     */
    public int complete(WizardFlow flow) throws IOException {
        Map<String, String> held = held();
        Map<String, String> changed = new LinkedHashMap<>();
        flow.settings().forEach((key, value) -> {
            if (!value.equals(held.getOrDefault(key, ""))) {
                changed.put(key, value);
            }
        });
        if (!changed.isEmpty()) {
            settings.saveAll(changed);
        }
        return changed.size();
    }

    private Map<String, SettingsAdmin.SettingView> views() throws IOException {
        return settings.wizardViews(Wizard.SETUP, null, true);
    }

    /** Whether the session is a super-admin's - who alone sets the deployment's settings and, among a repository's,
     *  the operator-only ones. */
    static boolean superadmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
