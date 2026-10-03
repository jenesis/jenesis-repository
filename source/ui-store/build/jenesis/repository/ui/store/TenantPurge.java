package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The complete removal of a tenant: its artifact-store scope {@code <tenant>/}, its credentials {@code auth/<tenant>/},
 * its audit trail {@code audit/<tenant>/} and its cache-side container, so a recreated tenant of the same name inherits
 * no live keys, history or artifacts. A paged tree delete through {@link ArtifactStore}, the name validated first. The
 * purge is audited in the operator scope, since it deletes the tenant's own audit space.
 *
 * <p>A tenant may hold millions of objects, so a surface {@linkplain #start starts} its deletion in the background and
 * reads its {@linkplain #deletion state} back: a {@link StoredReport} in the deployment's product space, so the state
 * outlives the tenant it describes and any node answers it.
 */
public class TenantPurge {

    private final TenantService tenants;
    private final ArtifactStore repositoryStore;
    private final Authorization authorization;
    private final AuditTrail audit;
    private final ConsoleActor actor;
    private final String operatorTenant;

    public TenantPurge(TenantService tenants, ArtifactStore repositoryStore, Authorization authorization,
                       AuditTrail audit, ConsoleActor actor, String operatorTenant) {
        this.tenants = tenants;
        this.repositoryStore = repositoryStore;
        this.authorization = authorization;
        this.audit = audit;
        this.actor = actor;
        this.operatorTenant = operatorTenant;
    }

    /**
     * Starts removing {@code tenant} in the background, answering whether it started: {@code false} when a deletion of
     * it is already under way. Its progress and outcome are {@link #deletion}'s. The actor is taken now, since the
     * deletion outlives the request that asked for it.
     */
    public boolean start(String tenant) throws IOException {
        String name = TenantService.validName(tenant);
        String who = actor.name();
        return StoredReport.compute(space(), report(name), () -> {
            long removed = delete(name, who);
            return new StoredReport.Rows((int) Math.min(removed, Integer.MAX_VALUE), List.of());
        });
    }

    /** The state of {@code tenant}'s last deletion - running, done with the objects it removed, or failed with the
     *  reason - or empty when it was never deleted. One point read. */
    public Optional<StoredReport.Report> deletion(String tenant) throws IOException {
        return StoredReport.read(space(), report(TenantService.validName(tenant)));
    }

    /**
     * Removes {@code tenant} completely, then audits it. Idempotent, so a partial delete re-runs to convergence.
     */
    public void delete(String tenant) throws IOException {
        delete(TenantService.validName(tenant), actor.name());
    }

    /** Removes {@code name}, audited as {@code who}, answering how many objects went. */
    private long delete(String name, String who) throws IOException {
        long removed = purge(repositoryStore, name)
                + purge(repositoryStore, Scopes.space(Scopes.AUTH) + "/" + name)
                + purge(repositoryStore, Scopes.space(Scopes.AUDIT) + "/" + name);
        tenants.delete(name);
        // The keys went behind the authorization's cache, which would otherwise keep authorizing them; the set is
        // unbounded, so the whole cache goes.
        authorization.forget();
        audit.record(operatorTenant, who, "tenant.purge", name);
        return removed;
    }

    /** Where a tenant's deletion is reported: the deployment's product space, which no tenant's deletion touches. */
    private ArtifactStore space() {
        return repositoryStore.scope(Scopes.SYSTEM);
    }

    private static String report(String tenant) {
        return "tenant-deletion-" + tenant;
    }

    /** The bounds one namespace is purged under. A purge must not stop short, so the entry cap is only the continuation
     *  {@link #purge} follows, and the step budget raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} instead. It drains at {@link BoundedChildren#DRAIN_PAGE}. */
    private static final PagedTreeWalk NAMESPACE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Deletes every object under {@code prefix}, answering how many. Deleting behind the cursor is safe, since the
     *  descent pages forward from the last key it delivered. */
    private static long purge(ArtifactStore store, String prefix) throws IOException {
        long[] removed = {0L};
        String cursor = null;
        while (true) {
            Traversal.Result result = NAMESPACE.walk(store, prefix, cursor, key -> {
                store.delete(key);
                removed[0]++;
            });
            if (result.exhausted()) {
                return removed[0];
            }
            cursor = result.cursor().orElseThrow();
        }
    }
}
