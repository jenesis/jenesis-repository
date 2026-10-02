package build.jenesis.repository.ui.admin;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.ui.admin.web.SetupWizard;

/**
 * Puts the console's own gate in the settings catalogue, so it appears in the generated reference and on the modules
 * screen. Declared not live: it decides whether the console's controllers and chain are registered, so it is read as
 * the context starts.
 */
public final class ConsoleSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(AdminConsoleNode.GATE, "Console", "Admin console",
                "Whether this deployment serves the admin console. Switched off, the console's screens and its "
                        + "sign-in chain are not registered at all and the node answers only the repository's own "
                        + "surfaces - which is the posture for a deployment that is operated through the API and the "
                        + "CLI, or one that runs its console elsewhere. Applies on restart.",
                Setting.Kind.BOOLEAN, "true", false).gate().standard(),
                new Setting(SetupWizard.SETTING, "First run", "First-run setup guide",
                        "Send a super-admin who signs in with the starter key to the first-run setup screen, which "
                                + "walks the decisions a new deployment should make: the starter credentials, the "
                                + "compliance verdicts, the advisory feeds, retention. A deployment provisioned from "
                                + "configuration switches it off here. The screen stays reachable as Setup, under "
                                + "Settings, either way; what says setup is finished is whether the starter credential "
                                + "is still in use. Applies live.",
                        Setting.Kind.BOOLEAN, SetupWizard.ON_BY_DEFAULT, true).standard(),
                new Setting(GITHUB_CLIENT_ID, "Sign-in", "GitHub client id",
                        "The client id of the GitHub OAuth app people sign in to the console with; empty, the "
                                + "sign-in page offers no GitHub button. Register the app on GitHub with the "
                                + "callback <console address>/login/oauth2/code/github - the first-run guide shows "
                                + "the exact address and saves both values for you. Applies to the next sign-in.",
                        Setting.Kind.STRING, "", true).standard().operator(),
                new Setting(GITHUB_CLIENT_SECRET, "Sign-in", "GitHub client secret",
                        "The client secret of the same GitHub OAuth app, stored encrypted with the settings master "
                                + "key (JENREPO_SECRETS_KEY). Applies to the next sign-in.",
                        Setting.Kind.SECRET, "", true).standard().operator());
    }

    /** The GitHub OAuth app's client id, the key {@code jenrepo.ui.github.client-id} binds at boot too. */
    public static final String GITHUB_CLIENT_ID = "ui.github.client-id";

    /** The GitHub OAuth app's client secret. */
    public static final String GITHUB_CLIENT_SECRET = "ui.github.client-secret";
}
