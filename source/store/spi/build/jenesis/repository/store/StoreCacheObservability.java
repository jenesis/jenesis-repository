package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/** Every {@link StoreCache}'s hits, misses and entries on the observability report - the counters that say whether
 *  the request-per-read model is holding, and what a clear threw away. Three node-wide totals under fixed names
 *  first, which is what a dashboard reads and what the generated observability reference can list; then each
 *  cache's own three under {@code jenrepo.cache.<name>}, named for the cache that is alive. */
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
        metrics.add(Metric.counter("jenrepo.cache.hits", "Reads of every store cache on this node answered without a "
                + "store round trip; with jenrepo.cache.misses, the share of point reads the store never saw.",
                hits, "reads"));
        metrics.add(Metric.counter("jenrepo.cache.misses", "Reads of every store cache on this node that went to the "
                + "store - every read when jenrepo.cache.ttl is 0.", misses, "reads"));
        metrics.add(Metric.gauge("jenrepo.cache.entries", "Documents and listings every store cache on this node "
                + "holds; POST /api/admin/caches/clear drops them.", entries, "entries"));
        MissMemory memory = MissMemory.node();
        metrics.add(Metric.counter("jenrepo.cache.misses.spared", "Serve probes this node answered as absent from its "
                + "memory of misses instead of reading the store - the pointer reads jenrepo.cache.miss-ttl spares.",
                memory.spared(), "reads"));
        metrics.add(Metric.counter("jenrepo.cache.misses.recorded", "Absent pointers this node read and remembered, "
                + "each for jenrepo.cache.miss-ttl; a write of the key on this node forgets it sooner.",
                memory.recorded(), "reads"));
        metrics.add(Metric.gauge("jenrepo.cache.misses.entries", "Keys this node currently remembers as absent, "
                + "bounded and dropped with the caches by POST /api/admin/caches/clear.", memory.size(), "entries"));
        DocumentMemory listings = DocumentMemory.node();
        metrics.add(Metric.counter("jenrepo.cache.documents.hits", "Listing reads this node served from its memory "
                + "of documents instead of the store - the reads jenrepo.cache.document-ttl spares a burst of builds.",
                listings.hits(), "reads"));
        metrics.add(Metric.counter("jenrepo.cache.documents.misses", "Listing reads this node took to the store and "
                + "remembered, each for jenrepo.cache.document-ttl; a write of the key on this node forgets it sooner.",
                listings.misses(), "reads"));
        metrics.add(Metric.gauge("jenrepo.cache.documents.bytes", "Bytes of listings this node currently remembers, "
                + "bounded and dropped with the caches by POST /api/admin/caches/clear.", listings.bytes(), "bytes"));
        UpstreamMemory relayed = UpstreamMemory.node();
        metrics.add(Metric.counter("jenrepo.cache.upstream.hits", "Relayed upstream documents - a proxied "
                + "maven-metadata.xml - this node answered from memory instead of fetching them again; the fetches "
                + "jenrepo.cache.upstream-ttl spares the upstream.", relayed.hits(), "reads"));
        metrics.add(Metric.counter("jenrepo.cache.upstream.misses", "Relayed upstream documents this node fetched and "
                + "remembered, each for jenrepo.cache.upstream-ttl.", relayed.misses(), "reads"));
        metrics.add(Metric.gauge("jenrepo.cache.upstream.bytes", "Bytes of relayed upstream documents this node "
                + "currently remembers, bounded and dropped with the caches by POST /api/admin/caches/clear.",
                relayed.bytes(), "bytes"));
        metrics.addAll(each);
        return metrics;
    }
}
