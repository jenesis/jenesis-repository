package build.jenesis.repository.ui.store;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.audit.AuditTrail;

/**
 * The super-admin, volume-wide disk reclaim behind the projects screen's cache-volume section: a least-recently-used
 * sweep across every tenant's cache on the shared root storage until the free-space target is met - the cross-tenant
 * counterpart of the per-project {@link CacheService} eviction, kept apart from it because it spans tenants (see
 * {@link CacheService}'s note that cross-tenant reclaim lives here, not in the tenant-confined cache service). It runs
 * the shared {@link Eviction#reclaim} policy and, because a reclaim is a privileged destructive mutation that deletes
 * cache entries across every tenant, writes an audit event (§6).
 *
 * <p>The reclaim belongs to no single tenant, so - like the server's {@code StoragePurgeController} and
 * {@link TenantPurge} - it is audited in the deployment <em>operator</em> scope rather than any one tenant's,
 * attributed to the acting super-admin, with the freed totals as the target. Best-effort by the trail's contract: a
 * failed audit write never fails the reclaim it records.
 */
public class VolumeReclaim {

    private final CacheStorage rootStorage;
    private final AuditTrail audit;
    private final ConsoleActor actor;
    private final String operatorTenant;

    public VolumeReclaim(CacheStorage rootStorage, AuditTrail audit, ConsoleActor actor, String operatorTenant) {
        this.rootStorage = rootStorage;
        this.audit = audit;
        this.actor = actor;
        this.operatorTenant = operatorTenant;
    }

    /** Reclaim across the whole volume until the free-space target ({@code minFree} bytes and/or {@code minFreePercent}
     *  free) is met, then record the reclaim in the operator scope with the freed totals as the target - so even a
     *  no-op reclaim (target already met, or no thresholds set) leaves a trace that a super-admin triggered it. */
    public Eviction.Result reclaim(long minFree, int minFreePercent) {
        Eviction.Result result = Eviction.reclaim(rootStorage, minFree, minFreePercent);
        audit.record(operatorTenant, actor.name(), "cache.reclaim",
                "root (" + result.entriesDeleted() + " entries, " + result.bytesFreed() + " bytes)");
        return result;
    }
}
