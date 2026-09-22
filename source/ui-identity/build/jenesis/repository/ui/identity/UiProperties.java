package build.jenesis.repository.ui.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the admin console, bound from the {@code jenreg.ui.*} properties - each reached from its own
 * {@code JENREG_UI_*} environment variable by relaxed binding, with the default beside the field rather than in a
 * properties file restating it.
 *
 * <p>Two keys are gone rather than moved. {@code jenreg.ui.root} was read by nothing at all, and the two
 * reclaim targets were fed from the build cache's {@code JENREG_CACHE_MIN_FREE*} variables - so setting the
 * cache's reaper target silently moved the console's global reclaim target too, which is a different decision
 * about a different sweep.
 *
 *   jenreg.ui.admins            comma-separated provider-qualified ids SEEDED as deployment administrators on
 *                               every boot (JENREG_UI_ADMINS). It is not a mirror: an id dropped from it keeps
 *                               its administration until the grant is revoked through the API, and an
 *                               administrator granted there is equally real. A '*' entry is refused at startup -
 *                               an admin is a holder of rights, and it names none
 *   jenreg.ui.min-free-bytes    global disk-reclaim target in bytes
 *   jenreg.ui.min-free-percent  global disk-reclaim target in percent
 */
@ConfigurationProperties(prefix = "jenreg.ui")
public class UiProperties {

    private String admins = "";
    private long minFreeBytes = 0;
    private int minFreePercent = 0;
    /** Bearer token an identity provider presents to the SCIM provisioning API; blank disables SCIM. */
    private String scimToken = "";


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
}
