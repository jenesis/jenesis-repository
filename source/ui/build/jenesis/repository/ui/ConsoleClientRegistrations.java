package build.jenesis.repository.ui;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import module java.base;

/**
 * Builds the console's OAuth2/OIDC client registrations from configuration values.
 *
 * <p>Two providers, and each is present only when it has been configured: the built-in GitHub provider once a client
 * id is set, and a generic OpenID Connect provider once an issuer and a client id are set. The OIDC one is
 * discovered through {@link OidcDiscovery}, so the endpoints come from the issuer's own document rather than from
 * configuration a deployment would have to keep in step with it.
 *
 * <p><b>Why this takes values rather than a properties object.</b> The registrations are the same wherever the
 * console runs, but where the values come from is not - one deployment binds them under one prefix, another under
 * per-provider prefixes with a different principal model behind them. Passing the values in keeps the part that is
 * genuinely shared in one place while leaving each console free to bind its own configuration, which is what stopped
 * this from being one builder before: it was copied instead, and the copies then drifted on the requested scopes.
 *
 * <p>The scopes are a parameter for that reason. {@code openid} is already set by discovery and is what marks the
 * login OIDC rather than plain OAuth2; a console that also renders display names asks for {@code profile} and
 * {@code email} on top, and a console that does not, does not.
 */
public final class ConsoleClientRegistrations {

    private ConsoleClientRegistrations() {
    }

    /** The built-in GitHub provider, or empty when no client id is configured. */
    public static Optional<ClientRegistration> github(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(CommonOAuth2Provider.GITHUB
                .getBuilder("github")
                .clientId(clientId.trim())
                .clientSecret(clientSecret == null ? "" : clientSecret.trim())
                .scope("read:user")
                .build());
    }

    /**
     * A generic OpenID Connect provider discovered from its issuer, or empty when the issuer or the client id is
     * unconfigured - both are needed, since discovery has nowhere to look without the first and nothing to identify
     * itself with without the second.
     */
    public static Optional<ClientRegistration> oidc(String issuerUri, String clientId, String clientSecret,
                                                    String clientName, List<String> scopes) {
        if (issuerUri == null || issuerUri.isBlank() || clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(OidcDiscovery.fromIssuerLocation(issuerUri.trim())
                .registrationId("oidc")
                .clientId(clientId.trim())
                .clientSecret(clientSecret == null ? "" : clientSecret.trim())
                .clientName(clientName == null ? "" : clientName.trim())
                .scope(scopes.toArray(String[]::new))
                .build());
    }
}
