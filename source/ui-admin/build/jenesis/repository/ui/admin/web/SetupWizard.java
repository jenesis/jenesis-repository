package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.settings.FirstRunSteps;
import build.jenesis.repository.ui.identity.StarterCredential;
import build.jenesis.repository.ui.store.SettingsAdmin;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The first-run setup guide's decisions: whether a session is sent to it, and what it renders.
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
 * <p><b>It renders the catalogue and never restates it.</b> Each step ({@link FirstRunSteps}) shows its settings
 * as the settings screen shows them - the setting's own description, its declared default, its effective value,
 * whether it applies live - through the same {@link SettingsAdmin} rows, and every save posts to the settings
 * screen's own save route. A key the catalogue does not carry is left out of the step rather than invented.
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

    /** The guide's steps with their settings rendered from the catalogue and the store - the rows the settings
     *  screen shows, for the keys each step names and the catalogue carries. A step none of whose settings this
     *  deployment carries is left out: it asks about a capability the image does not have, and a decision nobody
     *  can make is not a step. The starter-credential step names no setting and is always kept. */
    public List<Step> steps() throws IOException {
        List<Step> steps = new ArrayList<>();
        for (FirstRunSteps.Step step : FirstRunSteps.ALL) {
            List<SettingsAdmin.SettingView> views = settings.views(step.keys());
            if (views.isEmpty() && !step.id().equals(FirstRunSteps.STARTER_CREDENTIAL)) {
                continue;
            }
            steps.add(new Step(step.id(), step.title(), step.why(), views));
        }
        return steps;
    }

    /** One rendered step: the definition's id, title and reason, and the settings rows the deployment carries. */
    public record Step(String id, String title, String why, List<SettingsAdmin.SettingView> settings) {
    }

    private static boolean superadmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
