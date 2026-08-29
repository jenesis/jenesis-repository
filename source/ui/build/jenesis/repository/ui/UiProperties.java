package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the console, bound from {@code jenreg.ui.*}. The artifact store is selected the same way the
 * repository server selects it - a backend name resolved through {@code ArtifactStoreProvider}, reading its own
 * configuration (root / bucket / connection string) from the environment - so the console reads the very store the
 * server writes. The admins list carries provider-qualified ids ({@code github/<id>}, {@code oidc/<sub>}) that are
 * granted the admin role; when it is empty no signed-in user is an admin (the secure default - the console denies
 * writes until an admin is named), and the single {@code *} wildcard makes every authenticated user an admin (the
 * explicit open-console opt-out).
 *
 * <p>Sign-in is not configured here. The OAuth2 and OpenID Connect clients bind their own
 * {@link GithubProperties} and {@link OidcProperties}, under {@code jenreg.ui.github.*} and
 * {@code jenreg.ui.oidc.*} - which is what they always did on the admin console's side, while this class bound the
 * same keys a second time with identical fields and identical defaults. A mechanism owns its own configuration; a
 * console's properties are what the console itself reads.
 *
 *   jenreg.ui.store             the artifact-store backend name (JENREG_STORE), default filesystem
 *   jenreg.ui.admins            comma-separated provider-qualified admin ids, or * for everyone (JENREG_UI_ADMINS)
 */
@ConfigurationProperties(prefix = "jenreg.ui")
public class UiProperties {

    private String store = "filesystem";
    private String admins = "";

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getAdmins() {
        return admins;
    }

    public void setAdmins(String admins) {
        this.admins = admins;
    }
}
