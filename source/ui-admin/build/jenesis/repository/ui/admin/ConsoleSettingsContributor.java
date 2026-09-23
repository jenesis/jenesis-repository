package build.jenesis.repository.ui.admin;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.ui.admin.web.SetupWizard;

/**
 * Puts the console's own gate in the deployment settings catalogue, so it appears in the generated reference and on
 * the modules screen like every other dial.
 *
 * <p>It had no entry at all, and the reason is worth keeping: the console was its own process, so "do not run it"
 * was the dial and there was nothing to write down. On one node that stopped being true, and a capability with no
 * documented way to switch it off is one an operator discovers by reading the module graph.
 *
 * <p>Declared <b>not live</b>, which is the honest statement rather than a limitation. The gate decides whether the
 * console's controllers and its security chain are registered at all, so it is read as the context starts; a
 * settings screen that offered to apply it immediately would be offering something it cannot do - and the screen
 * offering it would be the one disappearing.
 */
public final class ConsoleSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(AdminConsoleNode.GATE, "Console", "Admin console",
                "Whether this deployment serves the admin console. On by default: an image carrying the console "
                        + "meant to serve it. Switched off, the console's screens and its sign-in chain are not "
                        + "registered at all and the node answers only the repository's own surfaces - which is the "
                        + "posture for a deployment that is operated through the API and the CLI, or one that runs "
                        + "its console elsewhere. Applies on restart.",
                Setting.Kind.BOOLEAN, "true", false).gate(),
                new Setting(SetupWizard.SETTING, "Console", "First-run setup guide",
                        "Send a super-admin who signs in with the starter key to the first-run setup screen, which "
                                + "walks the decisions a new deployment should make: the starter credentials, the "
                                + "compliance verdicts, the advisory feeds, retention. On by default, because the "
                                + "operator the screen is for is the one who does not know to look for it; a "
                                + "deployment provisioned from configuration, rebuilt by CI or started for the "
                                + "hundredth time switches it off here, once. The screen stays reachable from the "
                                + "Administration menu either way, and this is not what says setup is finished - "
                                + "that is whether the starter credential is still in use. Applies live.",
                        Setting.Kind.BOOLEAN, SetupWizard.ON_BY_DEFAULT, true));
    }
}
