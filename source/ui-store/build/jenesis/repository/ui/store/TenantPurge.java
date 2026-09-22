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
 * The complete removal of a tenant across both stores the console fronts. Deleting a tenant's cache-side container
 * ({@link TenantService#delete}) alone is not enough: a tenant name also scopes the shared artifact store, where its
 * repositories live under {@code <tenant>/} and - as siblings of the tenant scopes at the store root - its
 * credentials under {@code auth/<tenant>/} and its audit trail under {@code audit/<tenant>/}. Left behind, those
 * credentials still authorize and that history still reads, so a recreated tenant of the same name silently inherits
 * the prior tenant's live keys and audit log (scope reuse). This service purges all of them - the artifact-store
 * tenant scope, the credential namespace and the audit namespace - and then the cache-side container, so a deleted
 * tenant name leaves no residual auth, audit or artifacts and is safe to reuse.
 *
 * <p>The purge is a bounded, paged, iterative tree delete through the {@link ArtifactStore} abstraction (never a raw
 * filesystem or SDK call), so it runs identically on filesystem and object-store backends. The name is validated once, up front
 * (through the shared {@link TenantService#validName} rule), so an invalid or reserved name purges nothing.
 *
 * <p>A purge is a privileged, destructive mutation - it deletes a tenant's artifacts, credentials and audit history -
 * so it writes an audit event. The event is recorded in the <em>deployment/operator</em> audit scope, never the
 * purged tenant's own: the purge deletes {@code audit/<tenant>/}, so an event written there would be erased by the
 * very operation it records. This mirrors the server's {@code StoragePurgeController}, which audits its cross-tenant
 * reclaim under the operator tenant for the same reason.
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
     * Remove {@code tenant} completely: its artifact-store tenant scope ({@code <tenant>/}, holding every repository,
     * the quota counter and the per-tenant settings), its credentials ({@code auth/<tenant>/}), its audit trail
     * ({@code audit/<tenant>/}) and its cache-side container. Idempotent - purging an absent namespace is a no-op -
     * so a partly-completed prior delete can be re-run to convergence. The purge is audited in the operator scope
     * (see the class note) after the removal completes.
     */
    public void delete(String tenant) throws IOException {
        String name = TenantService.validName(tenant);
        purge(repositoryStore, name);
        purge(repositoryStore, Scopes.space(Scopes.AUTH) + "/" + name);
        purge(repositoryStore, Scopes.space(Scopes.AUDIT) + "/" + name);
        tenants.delete(name);
        // The purge removed auth keys through the STORE, behind the authorization's cache. Without this, that cache
        // goes on answering from grants whose objects are gone - a deleted tenant's credentials still authorizing
        // and its console members still reading as members, until the entries age out. Blunt on purpose: a purge
        // removes an unbounded set of keys, so there is no entry list to invalidate.
        authorization.forget();
        // Audited after the removal, and in the operator scope rather than audit/<name>/ (which the purge above just
        // deleted), so the record of this destructive cross-tenant action survives the very operation it describes.
        audit.record(operatorTenant, actor.name(), "tenant.purge", name);
    }

    /** The bounds the tenant purge descends one namespace under. A purge that stopped early would leave a deleted
     *  tenant's credentials or audit trail standing while the console reported the tenant gone - and a recreated
     *  tenant of the same name would inherit them, which is exactly the residual this service exists to remove. The
     *  entry cap is therefore only the per-call continuation {@link #purge} follows to exhaustion, and the binding
     *  bound is the step budget (one {@link ArtifactStore#exists} probe per opened node), which raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short - a loud failure an
     *  operator can retry, never a silently partial purge.
     *  It drains, so it pages at {@link BoundedChildren#DRAIN_PAGE}: on the filesystem store every page rescans
     *  its directory, and for a walk that follows its continuation to exhaustion the page width is the number
     *  of rescans. */
    private static final PagedTreeWalk NAMESPACE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Delete every object under {@code prefix}, and each backend's {@code delete} tidies the now-empty containers it
     *  leaves behind. An absent prefix holds no key and deletes nothing. Driven by the shared bounded tree walk
     *  rather than a hand-rolled work stack: iterative, so a pathologically deep artifact path cannot
     *  overflow the JVM stack, and paged, so a tenant with a million-key level is never listed whole into heap.
     *
     *  <p>Deleting behind the cursor is safe: the descent only ever pages forward from the last key it delivered, so a
     *  removed earlier key can never displace a later one. */
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
