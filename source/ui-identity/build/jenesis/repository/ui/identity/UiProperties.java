package build.jenesis.repository.ui.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the admin console, bound from {@code jenrepo.ui.*} - each reached from its {@code JENREPO_UI_*}
 * variable by relaxed binding, the default beside the field.
 *
 * <p>The console's reclaim targets are deliberately not fed from the build cache's {@code JENREPO_CACHE_MIN_FREE*}
 * variables: they govern a different sweep.
 *
 * <pre>
 *   jenrepo.ui.admins            comma-separated provider-qualified ids SEEDED as deployment administrators on
 *                                every boot (JENREPO_UI_ADMINS). Not a mirror: an id dropped from it keeps its
 *                                administration until revoked through the API. A '*' entry is refused at startup.
 *   jenrepo.ui.min-free-bytes    global disk-reclaim target in bytes
 *   jenrepo.ui.min-free-percent  global disk-reclaim target in percent
 * </pre>
 */
@ConfigurationProperties(prefix = "jenrepo.ui")
public class UiProperties {

    private String admins = "";
    private long minFreeBytes = 0;
    private int minFreePercent = 0;
    /** Bearer token an identity provider presents to the SCIM provisioning API; blank disables SCIM. */
    private String scimToken = "";
    /** The full-access administrator key that key sign-in accepts ({@code JENREPO_UI_ADMIN_KEY}); blank accepts
     *  none. */
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
