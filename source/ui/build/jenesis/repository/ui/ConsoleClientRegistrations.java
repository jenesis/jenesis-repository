package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * Builds the console's OAuth2/OIDC client registrations from configuration values: GitHub once a client id is set,
 * and an OpenID Connect provider, discovered through {@link OidcDiscovery}, once an issuer and a client id are set. It
 * takes values rather than a properties object so each console binds its own configuration, and the scopes beyond
 * discovery's {@code openid} as a parameter.
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
     * An OpenID Connect provider discovered from its issuer, or empty unless both the issuer and the client id are
     * configured.
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
