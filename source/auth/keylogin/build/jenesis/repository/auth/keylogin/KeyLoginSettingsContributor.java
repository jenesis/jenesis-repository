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
                "Whether the console accepts a pasted login key as a sign-in method - a demo / simple-deployment "
                        + "on-ramp usable without SSO, disabled by default. When on, an operator may set a full-access "
                        + "bootstrap admin key (JENREG_UI_ADMIN_KEY) and issue scoped login keys from the admin API. "
                        + "Prefer single sign-on (OIDC) or a directory (LDAP) in production. Applies on restart.",
                Setting.Kind.BOOLEAN, "false", false).gate());
    }
}
