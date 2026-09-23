package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.StoreCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read caches over the store ({@link StoreCache}): what this node holds, and the one call that drops it all.
 *
 * <p><strong>Node-local for the listings, fleet-wide for the grants.</strong> Dropping a cache on every node would
 * be the unbounded fan-out the read rules forbid, so this empties the caches of the node that served the request
 * and every other node keeps its own until its ttl. But the reason an operator reaches for this is almost always a
 * revoked credential another node is still honouring inside that ttl - which is exactly the case a node-local
 * clear cannot fix, so the endpoint promised something it could not do.
 *
 * <p>{@link Authorization#invalidateAcrossNodes()} closes that half without a fan-out: it bumps one small document
 * that every node reads on its own schedule, so an authorization decision anywhere follows within the auth epoch's
 * few-second ttl. The answer says which half went where, because "cleared" over a fleet is otherwise read as more
 * than it is. The console's button and the CLI's {@code caches clear} reach the same pair.
 */
@RestController
public class CachesAdminController {

    private final AuditTrail audit;
    private final Authorization authorization;
    private final String operatorTenant;

    public CachesAdminController(AuditTrail audit, Authorization authorization, RepositoryProperties properties) {
        this.audit = audit;
        this.authorization = authorization;
        this.operatorTenant = properties.getOperatorTenant().isBlank()
                ? properties.getDefaultTenant()
                : properties.getOperatorTenant();
    }

    /** Every cache on this node: its ttl, hits, misses and entries. */
    @GetMapping("/api/admin/caches")
    public CachesView caches() {
        return new CachesView(node(), views());
    }

    /** Drop every entry of every cache on this node, and every node's authorization cache; answers what went where. */
    @PostMapping("/api/admin/caches/clear")
    public ClearedView clear(@RequestHeader(value = Repositories.KEY, required = false) String key) throws IOException {
        int cleared = StoreCache.clearAll();
        // Ordered: this node's caches are already empty, so the bump is what reaches the others. It is deliberately
        // not conditional on `cleared` - a node holding nothing still has peers that hold a revoked grant.
        boolean grants = authorization.invalidateAcrossNodes();
        audit.record(operatorTenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.CACHES_CLEAR,
                node() + " (" + cleared + " entries)");
        return new ClearedView(node(), cleared, grants, views());
    }

    private static List<CacheView> views() {
        List<CacheView> views = new ArrayList<>();
        for (StoreCache cache : StoreCache.caches()) {
            views.add(new CacheView(cache.name(), cache.ttl().toString(), cache.hits(), cache.misses(), cache.size()));
        }
        views.sort(Comparator.comparing(CacheView::name));
        return views;
    }

    private static String node() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException unknown) {
            return "this node";
        }
    }

    public record CacheView(String name, String ttl, long hits, long misses, int entries) {
    }

    public record CachesView(String node, List<CacheView> caches) {
    }

    /** What a clear did: whose caches were emptied, how many entries went, and whether the grants half reached the
     *  rest of the fleet. The last is not decoration - an open deployment holds no grants and keeps no epoch, so it
     *  answers {@code false}, and without it a caller reads one node's count as a fleet-wide result. */
    public record ClearedView(String node, int cleared, boolean grantsEverywhere, List<CacheView> caches) {
    }
}
