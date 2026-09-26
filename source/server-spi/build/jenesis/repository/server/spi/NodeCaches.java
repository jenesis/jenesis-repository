package build.jenesis.repository.server.spi;

import module java.base;
import build.jenesis.repository.store.StoreCache;

/**
 * The read caches over the store ({@link StoreCache}) as an operator sees them: what this node holds, and the one act
 * that drops it all. The API, the console and the CLI reach this one implementation, and each records the act in its
 * own caller's name.
 *
 * <p><strong>Node-local for the listings, fleet-wide for the grants.</strong> Dropping a cache on every node would be
 * the unbounded fan-out the read rules forbid, so a clear empties the caches of the node that served the request and
 * every other node keeps its own until its ttl. But the reason an operator reaches for this is almost always a revoked
 * credential another node is still honouring inside that ttl, which a node-local clear cannot fix - so the clear also
 * calls {@link Authorization#invalidateAcrossNodes()}, which bumps one small document every node reads on its own
 * schedule. The answer says which half went where, because "cleared" over a fleet is otherwise read as more than it
 * is.
 */
public final class NodeCaches {

    private NodeCaches() {
    }

    /** One cache on this node: its ttl, and what it has served and holds. */
    public record Cache(String name, String ttl, long hits, long misses, int entries) {
    }

    /** What a clear did: whose caches were emptied, how many entries went, and whether the grants half reached the
     *  rest of the fleet. The last is not decoration - an open deployment holds no grants and keeps no epoch, so it
     *  answers {@code false}, and without it a caller reads one node's count as a fleet-wide result. */
    public record Cleared(String node, int cleared, boolean grantsEverywhere) {
    }

    /** Every cache on this node, by name. */
    public static List<Cache> caches() {
        List<Cache> caches = new ArrayList<>();
        for (StoreCache cache : StoreCache.caches()) {
            caches.add(new Cache(cache.name(), cache.ttl().toString(), cache.hits(), cache.misses(), cache.size()));
        }
        caches.sort(Comparator.comparing(Cache::name));
        return caches;
    }

    /** Drop every entry of every cache on this node, then every node's authorization cache. */
    public static Cleared clear(Authorization authorization) throws IOException {
        int cleared = StoreCache.clearAll();
        // Ordered: this node's caches are already empty, so the bump is what reaches the others. It is deliberately
        // not conditional on `cleared` - a node holding nothing still has peers that hold a revoked grant.
        boolean grants = authorization.invalidateAcrossNodes();
        return new Cleared(node(), cleared, grants);
    }

    /** The name this node answers to, for an operator reading which node's caches a figure is. */
    public static String node() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException unknown) {
            return "this node";
        }
    }
}
