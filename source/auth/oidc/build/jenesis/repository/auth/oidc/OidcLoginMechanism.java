package build.jenesis.repository.auth.oidc;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.OAuth2ClientConfig;

/**
 * Discovers the OIDC/OAuth2 sign-in mechanism: the console imports {@link OAuth2ClientConfig} exactly like a Boot
 * auto-configuration, whose conditions keep every bean away until a provider is actually configured - so this
 * module installed but unconfigured still means "sign-in not configured", matching the console's behaviour from
 * before the mechanism was a module.
 *
 * <p>The configuration it names lives in the console module, and this module is only the statement that a console
 * wants the mechanism <em>optional</em>. It used to carry a copy: the same condition, the same contributor, the same
 * registration builder, and a second binding of {@code jenreg.ui.github.*} and {@code jenreg.ui.oidc.*} with
 * identical fields and identical defaults. Only the requested scopes differed, and that difference was a
 * consequence of one console rendering a signed-in user's qualified id where the other rendered their name.
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
