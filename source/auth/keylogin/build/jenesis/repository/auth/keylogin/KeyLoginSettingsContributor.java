package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Lists the key-login module's one dial - the {@code key-login} gate - in the settings catalogue, so it appears exactly
 * when this module is installed and pairs the module with its toggle (the {@link Setting#gate()} convention). The
 * environment's admin key ({@code JENREPO_UI_ADMIN_KEY}) is not catalogued: a deploy-time secret is never a settable
 * setting, so no settings API or screen can carry it.
 */
public final class KeyLoginSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(KeyLoginMechanism.NAME, "Console", "Key-based console login",
                "Whether the console accepts a pasted login key as a sign-in method - the way into a deployment "
                        + "before single sign-on is set up, on by default. A deployment nobody can sign in to yet "
                        + "prints a one-time key at start, valid for an hour; an operator may also set a full-access "
                        + "admin key (JENREPO_UI_ADMIN_KEY) and issue scoped login keys. Switch it off once single "
                        + "sign-on (OIDC) or a directory (LDAP) signs people in. Applies on restart.",
                Setting.Kind.BOOLEAN, KeyLoginMechanism.ON_BY_DEFAULT, false).gate().essential());
    }
}
