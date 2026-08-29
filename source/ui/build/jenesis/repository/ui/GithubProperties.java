package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub OAuth client credentials, bound from {@code jenreg.ui.github.*} ({@code JENREG_UI_GITHUB_CLIENT_ID} /
 * {@code _SECRET}) - the same keys as before this mechanism was a module. When the client id is blank, GitHub login
 * is disabled.
 */
@ConfigurationProperties(prefix = "jenreg.ui.github")
public class GithubProperties {

    private String clientId = "";
    private String clientSecret = "";

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
}
