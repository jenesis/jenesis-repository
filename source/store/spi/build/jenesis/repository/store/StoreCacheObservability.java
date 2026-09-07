package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/** Every {@link StoreCache}'s hits, misses and entries on the observability report - the counters that say whether
 *  the request-per-read model is holding, and what a clear threw away. Three node-wide totals under fixed names
 *  first, which is what a dashboard reads and what the generated observability reference can list; then each
 *  cache's own three under {@code jenreg.cache.<name>}, named for the cache that is alive. */
public final class StoreCacheObservability implements ObservabilitySource {

    @Override
    public List<Metric> metrics() {
        long hits = 0;
        long misses = 0;
        long entries = 0;
        List<Metric> each = new ArrayList<>();
        for (StoreCache cache : StoreCache.caches()) {
            hits += cache.hits();
            misses += cache.misses();
            entries += cache.size();
            each.addAll(cache.metrics());
        }
        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.counter("jenreg.cache.hits", "Reads of every store cache on this node answered without a "
                + "store round trip; with jenreg.cache.misses, the share of point reads the store never saw.",
                hits, "reads"));
        metrics.add(Metric.counter("jenreg.cache.misses", "Reads of every store cache on this node that went to the "
                + "store - every read when jenreg.cache.ttl is 0.", misses, "reads"));
        metrics.add(Metric.gauge("jenreg.cache.entries", "Documents and listings every store cache on this node "
                + "holds; POST /api/admin/caches/clear drops them.", entries, "entries"));
        metrics.addAll(each);
        return metrics;
    }
}
