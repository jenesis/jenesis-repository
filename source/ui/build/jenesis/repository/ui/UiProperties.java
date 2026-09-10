package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the console, bound from {@code jenreg.ui.*}. The artifact store is selected the same way the
 * repository server selects it - a backend name resolved through {@code ArtifactStoreProvider}, reading its own
 * configuration (root / bucket / connection string) from the environment - so the console reads the very store the
 * server writes.
 *
 * <p>The admins list carries provider-qualified ids ({@code github/<id>}, {@code oidc/<sub>}), and it is a
 * <b>boot seed rather than the source of truth</b>: each id named here is granted deployment-wide administration
 * on every start, and the answer to "who administers this deployment" is then read back from those grants. So an
 * id dropped from the list keeps its administration until the grant is revoked, and one granted through the API
 * is an administrator the list never mentioned. When the list is empty and nothing has been granted, no signed-in
 * user is an admin - the secure default, under which the console denies writes until an administrator exists. A
 * {@code *} entry is refused at startup rather than honoured: an administrator is a holder of rights, and a
 * wildcard names no holder.
 *
 * <p>Sign-in is not configured here. The OAuth2 and OpenID Connect clients bind their own
 * {@link GithubProperties} and {@link OidcProperties}, under {@code jenreg.ui.github.*} and
 * {@code jenreg.ui.oidc.*} - which is what they always did on the admin console's side, while this class bound the
 * same keys a second time with identical fields and identical defaults. A mechanism owns its own configuration; a
 * console's properties are what the console itself reads.
 *
 *   jenreg.ui.store             the artifact-store backend name (JENREG_STORE), default filesystem
 *   jenreg.ui.admins            comma-separated provider-qualified ids SEEDED as deployment administrators on
 *                               every boot (JENREG_UI_ADMINS); not a mirror - removing one does not revoke it,
 *                               and a '*' entry is refused at startup, an admin being a holder and it naming none
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
