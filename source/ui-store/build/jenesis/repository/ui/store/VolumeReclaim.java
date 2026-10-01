package build.jenesis.repository.ui.store;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.audit.AuditTrail;

/**
 * The super-admin's volume-wide disk reclaim: {@link Eviction#reclaim} across every tenant's cache until the free-space
 * target is met, the cross-tenant counterpart of {@link CacheService}'s eviction. It belongs to no tenant, so it is
 * audited in the operator scope, best-effort.
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

    /** Reclaims until {@code minFree} bytes and {@code minFreePercent} are free, then records the freed totals, even for
     *  a no-op. */
    public Eviction.Result reclaim(long minFree, int minFreePercent) {
        Eviction.Result result = Eviction.reclaim(rootStorage, minFree, minFreePercent);
        audit.record(operatorTenant, actor.name(), "cache.reclaim",
                "root (" + result.entriesDeleted() + " entries, " + result.bytesFreed() + " bytes)");
        return result;
    }
}
