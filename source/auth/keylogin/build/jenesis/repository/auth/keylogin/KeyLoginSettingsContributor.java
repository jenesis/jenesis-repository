package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Lists the key-login module's one runtime dial - the {@code key-login} enable gate - in the deployment settings
 * catalogue, discovered by {@link ServiceLoader} so it appears exactly when this module is installed and pairs the
 * module with its on/off toggle on the modules screen (the {@link Setting#gate()} convention). The env bootstrap admin
 * key ({@code JENREG_UI_ADMIN_KEY}) is deliberately not catalogued: it is a deploy-time secret, never a store-writable
 * setting, so it can never be set through the settings API nor appear on a settings surface at all - the strongest form
 * of "redacted".
 */
public final class KeyLoginSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(KeyLoginMechanism.NAME, "Console", "Key-based console login",
                "Whether the console accepts a pasted login key as a sign-in method - the way into a deployment "
                        + "before single sign-on is set up, on by default. A deployment nobody can sign in to yet "
                        + "prints a one-time key at start, valid for an hour; an operator may also set a full-access "
                        + "admin key (JENREG_UI_ADMIN_KEY) and issue scoped login keys. Switch it off once single "
                        + "sign-on (OIDC) or a directory (LDAP) signs people in. Applies on restart.",
                Setting.Kind.BOOLEAN, KeyLoginMechanism.ON_BY_DEFAULT, false).gate());
    }
}
