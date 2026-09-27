package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The signed-in tenant's usage ceilings for the console: the settings of the {@value #GROUP} group - its storage quota
 * and its request rate limit, tenant settings whose deployment value is every tenant's default - read and written
 * through the settings catalogue ({@link SettingsAdmin}), with the stored bytes recounted against the quota beside
 * them. A tenant administrator edits these and nothing else of the tenant's settings, which are the operator's.
 */
public class TenantLimits extends TenantScope {

    /** The settings group this screen edits: the ceilings a tenant's repositories share. */
    public static final String GROUP = "Limits";

    /** The quota's setting key, which the usage meter is read against. */
    private static final String QUOTA = "tenant-quota";

    private final SettingsAdmin settings;

    public TenantLimits(ArtifactStore repositoryStore, SettingsAdmin settings, CurrentTenant current,
                        ObservationRegistry observations, AuditTrail audit, ConsoleActor actor) {
        super(repositoryStore, current, observations, audit, actor);
        this.settings = settings;
    }

    /** The signed-in tenant's storage quota: the effective byte ceiling ({@code 0} when unlimited) and the bytes
     *  stored. The usage total is not recomputed here - that walks every blob of every repository the tenant owns -
     *  so a newly set or lowered quota is judged against the count the scheduled cleanup last took. */
    public QuotaView quota() throws IOException {
        String ceiling = settings.effective(tenant(), QUOTA, "0");
        long maxBytes;
        try {
            maxBytes = Long.parseLong(ceiling.trim());
        } catch (NumberFormatException _) {
            maxBytes = 0;
        }
        return new QuotaView(maxBytes, new QuotaArtifactStore(root.scope(tenant()), 0).used());
    }

    /** The {@value #GROUP} settings as this tenant resolves them, each with the deployment's value as its baseline. */
    public List<SettingsAdmin.Group> groups() throws IOException {
        return settings.groups(tenant()).stream().filter(group -> GROUP.equals(group.name())).toList();
    }

    /** Set or clear one of this tenant's {@value #GROUP} settings through the catalogue; any other key is refused, since
     *  the rest of a tenant's settings are the operator's to change. */
    public void save(String key, String value) throws IOException {
        boolean ceiling = groups().stream().flatMap(group -> group.settings().stream())
                .anyMatch(setting -> setting.key().equals(key));
        if (!ceiling) {
            throw new IllegalArgumentException("'" + key + "' is not one of this tenant's limits.");
        }
        settings.save(tenant(), key, value);
    }

    /** The tenant's storage quota for the console: the byte ceiling ({@code 0} unlimited) and the bytes stored. */
    public record QuotaView(long maxBytes, long usedBytes) {
    }
}
