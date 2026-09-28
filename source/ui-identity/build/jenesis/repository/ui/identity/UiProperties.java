package build.jenesis.repository.ui.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the admin console, bound from the {@code jenrepo.ui.*} properties - each reached from its own
 * {@code JENREPO_UI_*} environment variable by relaxed binding, with the default beside the field rather than in a
 * properties file restating it.
 *
 * <p>The console's reclaim targets are not fed from the build cache's {@code JENREPO_CACHE_MIN_FREE*} variables:
 * setting the cache's reaper target must not silently move the console's global reclaim target, which is a
 * different decision about a different sweep.
 *
 *   jenrepo.ui.admins            comma-separated provider-qualified ids SEEDED as deployment administrators on
 *                               every boot (JENREPO_UI_ADMINS). It is not a mirror: an id dropped from it keeps
 *                               its administration until the grant is revoked through the API, and an
 *                               administrator granted there is equally real. A '*' entry is refused at startup -
 *                               an admin is a holder of rights, and it names none
 *   jenrepo.ui.min-free-bytes    global disk-reclaim target in bytes
 *   jenrepo.ui.min-free-percent  global disk-reclaim target in percent
 */
@ConfigurationProperties(prefix = "jenrepo.ui")
public class UiProperties {

    private String admins = "";
    private long minFreeBytes = 0;
    private int minFreePercent = 0;
    /** Bearer token an identity provider presents to the SCIM provisioning API; blank disables SCIM. */
    private String scimToken = "";
    /** The full-access administrator key key sign-in accepts ({@code JENREPO_UI_ADMIN_KEY}); blank accepts none. */
    private String adminKey = "";


    public String getAdmins() {
        return admins;
    }

    public void setAdmins(String admins) {
        this.admins = admins;
    }

    public long getMinFreeBytes() {
        return minFreeBytes;
    }

    public void setMinFreeBytes(long minFreeBytes) {
        this.minFreeBytes = minFreeBytes;
    }

    public int getMinFreePercent() {
        return minFreePercent;
    }

    public void setMinFreePercent(int minFreePercent) {
        this.minFreePercent = minFreePercent;
    }

    public String getScimToken() {
        return scimToken;
    }

    public void setScimToken(String scimToken) {
        this.scimToken = scimToken;
    }

    public String getAdminKey() {
        return adminKey;
    }

    public void setAdminKey(String adminKey) {
        this.adminKey = adminKey;
    }
}
