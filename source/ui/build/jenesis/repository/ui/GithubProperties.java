package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub OAuth client credentials, bound from {@code jenrepo.ui.github.*} ({@code JENREPO_UI_GITHUB_CLIENT_ID} /
 * {@code _SECRET}). When the client id is blank, GitHub login
 * is disabled.
 */
@ConfigurationProperties(prefix = "jenrepo.ui.github")
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
