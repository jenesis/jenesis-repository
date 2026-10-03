package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.NodeCaches;

/**
 * The console's clear of the read caches: {@link NodeCaches#clear}, as {@code POST /api/admin/caches/clear} makes it;
 * and its write of what the node holds, {@link NodeCaches#writeHeld}, as {@code POST /api/admin/caches/flush} makes
 * it - each audited in the operator scope since what it touches belongs to no tenant.
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

    /** Ask every write this node holds to land now, and record it. */
    public NodeCaches.Written flush() {
        NodeCaches.Written written = NodeCaches.writeHeld();
        audit.record(operatorTenant, actor.name(), AuditActions.CACHES_FLUSH,
                written.node() + " (" + written.asked().stream().mapToLong(held -> held.pending()).sum() + " held)");
        return written;
    }

    /** Clear this node's caches and every node's grants, and record it. */
    public NodeCaches.Cleared clear() throws IOException {
        NodeCaches.Cleared cleared = NodeCaches.clear(authorization);
        audit.record(operatorTenant, actor.name(), AuditActions.CACHES_CLEAR,
                cleared.node() + " (" + cleared.cleared() + " entries)");
        return cleared;
    }
}
