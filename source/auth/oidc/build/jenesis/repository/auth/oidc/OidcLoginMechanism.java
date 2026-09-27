package build.jenesis.repository.auth.oidc;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.OAuth2ClientConfig;

/**
 * Discovers the OIDC/OAuth2 sign-in mechanism: the console imports {@link OAuth2ClientConfig} exactly like a Boot
 * auto-configuration, whose conditions keep every bean away until a provider is actually configured - so this
 * module installed but unconfigured still means "sign-in not configured".
 *
 * <p>The configuration it names lives in the console module, and this module is only the statement that a console
 * wants the mechanism <em>optional</em>. It carries no copy of the condition, the contributor, the registration
 * builder or the {@code jenreg.ui.github.*} and {@code jenreg.ui.oidc.*} binding.
 */
public final class OidcLoginMechanism implements ConsoleModuleProvider {

    @Override
    public String name() {
        return "oidc";
    }

    @Override
    public Class<?> configuration() {
        return OAuth2ClientConfig.class;
    }
}
