package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * An OpenID Connect provider, bound from {@code jenrepo.ui.oidc.*}: its issuer URI, from which the endpoints and key set
 * are discovered, client id and secret, and the button's {@code name}. Disabled while the issuer or client id is blank.
 * Members are keyed {@code oidc/<sub>}.
 */
@ConfigurationProperties(prefix = "jenrepo.ui.oidc")
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
