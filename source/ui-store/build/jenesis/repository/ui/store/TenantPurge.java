package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The complete removal of a tenant: its artifact-store scope {@code <tenant>/}, its credentials {@code auth/<tenant>/},
 * its audit trail {@code audit/<tenant>/} and its cache-side container, so a recreated tenant of the same name inherits
 * no live keys, history or artifacts. A paged tree delete through {@link ArtifactStore}, the name validated first. The
 * purge is audited in the operator scope, since it deletes the tenant's own audit space.
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
     * Removes {@code tenant} completely, then audits it. Idempotent, so a partial delete re-runs to convergence.
     */
    public void delete(String tenant) throws IOException {
        String name = TenantService.validName(tenant);
        purge(repositoryStore, name);
        purge(repositoryStore, Scopes.space(Scopes.AUTH) + "/" + name);
        purge(repositoryStore, Scopes.space(Scopes.AUDIT) + "/" + name);
        tenants.delete(name);
        // The keys went behind the authorization's cache, which would otherwise keep authorizing them; the set is
        // unbounded, so the whole cache goes.
        authorization.forget();
        audit.record(operatorTenant, actor.name(), "tenant.purge", name);
    }

    /** The bounds one namespace is purged under. A purge must not stop short, so the entry cap is only the continuation
     *  {@link #purge} follows, and the step budget raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} instead. It drains at {@link BoundedChildren#DRAIN_PAGE}. */
    private static final PagedTreeWalk NAMESPACE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Deletes every object under {@code prefix}. Deleting behind the cursor is safe, since the descent pages forward
     *  from the last key it delivered. */
    private static void purge(ArtifactStore store, String prefix) throws IOException {
        String cursor = null;
        while (true) {
            Traversal.Result result = NAMESPACE.walk(store, prefix, cursor, store::delete);
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }
}
