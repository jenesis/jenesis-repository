package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A generic OpenID Connect provider, bound from {@code jenreg.ui.oidc.*} ({@code JENREG_UI_OIDC_ISSUER_URI} /
 * {@code _CLIENT_ID} / {@code _CLIENT_SECRET} / {@code _NAME}) - the same keys as before this mechanism was a
 * module. Configured by its issuer URI (the rest - authorization, token and user-info endpoints, JWK set - is
 * discovered). When the issuer or client id is blank, OIDC login is disabled. Members are keyed
 * {@code oidc/<sub>}; {@code name} labels the button.
 */
@ConfigurationProperties(prefix = "jenreg.ui.oidc")
public class OidcProperties {

    private String issuerUri = "";
    private String clientId = "";
    private String clientSecret = "";
    private String name = "Single sign-on";

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
