package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the console, bound from {@code jenrepo.ui.*}. The store is selected as the server selects it,
 * through {@code ArtifactStoreProvider}, so the console reads the store the server writes.
 *
 * <p>{@code jenrepo.ui.admins} lists provider-qualified ids ({@code github/<id>}, {@code oidc/<sub>}) seeded as
 * deployment administrators on every boot ({@link ConsoleAdministrators}); with none seeded or granted, the console
 * denies writes. Sign-in mechanisms bind their own configuration ({@link GithubProperties}, {@link OidcProperties}).
 */
@ConfigurationProperties(prefix = "jenrepo.ui")
public class UiProperties {

    private String admins = "";

    public String getAdmins() {
        return admins;
    }

    public void setAdmins(String admins) {
        this.admins = admins;
    }
}
