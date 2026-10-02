package build.jenesis.repository.auth.oidc;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.OAuth2ClientConfig;

/**
 * The OIDC/OAuth2 sign-in mechanism: the console imports {@link OAuth2ClientConfig}, whose conditions keep every bean
 * away until a provider is configured. The configuration lives in the console module; this module only makes the
 * mechanism optional.
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
