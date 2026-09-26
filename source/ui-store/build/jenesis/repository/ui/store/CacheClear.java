package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.NodeCaches;

/**
 * The console's clear of the read caches, behind the Caches screen: the same {@link NodeCaches#clear} the API's
 * {@code POST /api/admin/caches/clear} makes, recorded as that one is - in the deployment <em>operator</em> scope,
 * since what it drops belongs to no single tenant - but attributed to the acting console user. Best-effort by the
 * trail's contract: a failed audit write never fails the clear it records.
 */
public class CacheClear {

    private final Authorization authorization;
    private final AuditTrail audit;
    private final ConsoleActor actor;
    private final String operatorTenant;

    public CacheClear(Authorization authorization, AuditTrail audit, ConsoleActor actor, String operatorTenant) {
        this.authorization = authorization;
        this.audit = audit;
        this.actor = actor;
        this.operatorTenant = operatorTenant;
    }

    /** Clear this node's caches and every node's grants, and record it. */
    public NodeCaches.Cleared clear() throws IOException {
        NodeCaches.Cleared cleared = NodeCaches.clear(authorization);
        audit.record(operatorTenant, actor.name(), AuditActions.CACHES_CLEAR,
                cleared.node() + " (" + cleared.cleared() + " entries)");
        return cleared;
    }
}
