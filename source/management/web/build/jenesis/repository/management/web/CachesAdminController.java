package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.NodeCaches;
import build.jenesis.repository.store.HeldWrites;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read caches over the store, at the API: what this node holds, and the call dropping it all - node-local for the
 * listings, fleet-wide for the grants, as {@link NodeCaches} says - and the writes it holds in memory, with the call
 * asking them to land now. Recorded under the calling key in the tenant the
 * routing answers; the console's Caches screen and the CLI's {@code caches clear} reach the same implementation.
 */
@RestController
public class CachesAdminController {

    private final AuditTrail audit;
    private final Authorization authorization;
    private final RepositoryRouting routing;

    public CachesAdminController(AuditTrail audit, Authorization authorization, RepositoryRouting routing) {
        this.audit = audit;
        this.authorization = authorization;
        this.routing = routing;
    }

    /** Every cache on this node: its ttl, hits, misses and entries; and every write it holds unwritten. */
    @GetMapping("/api/admin/caches")
    public CachesView caches() {
        return new CachesView(NodeCaches.node(), NodeCaches.caches(), NodeCaches.held());
    }

    /** Drop every entry of every cache on this node, and every node's authorization cache; answers what went where. */
    @PostMapping("/api/admin/caches/clear")
    public ClearedView clear(@RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request) throws IOException {
        String tenant = routing.tenant(request);
        NodeCaches.Cleared cleared = NodeCaches.clear(authorization);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.CACHES_CLEAR,
                cleared.node() + " (" + cleared.cleared() + " entries)");
        return new ClearedView(cleared.node(), cleared.cleared(), cleared.grantsEverywhere(), NodeCaches.caches());
    }

    /**
     * Ask every write this node holds in memory - download counts, credential use, deferred counters - to land now
     * rather than on its cadence; answers what each held when asked. The writes land on their holders' own threads
     * within moments, so the answer is the request, and {@code GET /api/admin/caches} shows what is still held.
     */
    @PostMapping("/api/admin/caches/flush")
    public FlushedView flush(@RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request) {
        String tenant = routing.tenant(request);
        NodeCaches.Written written = NodeCaches.writeHeld();
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.CACHES_FLUSH,
                written.node() + " (" + written.asked().stream().mapToLong(HeldWrites.Held::pending).sum()
                        + " held)");
        return new FlushedView(written.node(), written.asked());
    }

    public record CachesView(String node, List<NodeCaches.Cache> caches, List<HeldWrites.Held> held) {
    }

    /** What a flush asked to land, on which node. */
    public record FlushedView(String node, List<HeldWrites.Held> asked) {
    }

    /** What a clear did, and what this node holds after it. */
    public record ClearedView(String node, int cleared, boolean grantsEverywhere, List<NodeCaches.Cache> caches) {
    }
}
