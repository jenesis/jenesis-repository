package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The signed-in tenant's usage ceilings for the console: its storage quota (with the stored bytes recounted against
 * it) and its request rate limit, read and written through the console's {@link Authorization} the same way the
 * repository server enforces them.
 */
public class TenantLimits extends TenantScope {

    private final Authorization authorization;

    public TenantLimits(ArtifactStore repositoryStore, Authorization authorization, CurrentTenant current,
                        ObservationRegistry observations, AuditTrail audit, ConsoleActor actor) {
        super(repositoryStore, current, observations, audit, actor);
        this.authorization = authorization;
    }

    /** The signed-in tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes stored. */
    public QuotaView quota() throws IOException {
        return new QuotaView(authorization.quota(tenant()),
                new QuotaArtifactStore(root.scope(tenant()), 0).used());
    }

    /**
     * Set ({@code > 0}) or clear ({@code 0}) the tenant's quota in bytes. The usage total is deliberately not
     * recomputed here: that walks every blob of every repository the tenant owns, so the cost of setting a limit would
     * grow with the tenant. The cleanup pass recomputes it for any tenant that has a limit, so deferring costs a
     * window rather than the number: enforcement runs on the previous total until the next pass, and a limit lowered
     * mid-window can be briefly over-admitted against. Carrying the total on the publish path instead would put a
     * contended counter on a far hotter path than this one.
     */
    public void setQuota(long maxBytes) throws IOException {
        // Matches ManagementController's quota.set event and its byte-count target.
        audit(AuditActions.QUOTA_SET, Long.toString(maxBytes));
        observe("set-quota", "*", _ -> {
            authorization.setQuota(tenant(), maxBytes);
            return null;
        });
    }

    /** The signed-in tenant's request rate ceiling in permits per minute, or {@code 0} for the deployment default. */
    public long rateLimit() throws IOException {
        return authorization.rateLimit(tenant());
    }

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's request rate ceiling in permits per minute. */
    public void setRateLimit(long permitsPerMinute) throws IOException {
        // Matches ManagementController's rate-limit.set event and its permits-per-minute target.
        audit("rate-limit.set", Long.toString(permitsPerMinute));
        observe("set-rate-limit", "*", _ -> {
            authorization.setRateLimit(tenant(), permitsPerMinute);
            return null;
        });
    }

    /** The tenant's storage quota for the console: the byte ceiling ({@code 0} unlimited) and the bytes stored. */
    public record QuotaView(long maxBytes, long usedBytes) {
    }
}
