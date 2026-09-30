package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.search.LicenseFacet;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Binds the Lucene search index to a repository's scoped store, discovered through {@code ServiceLoader}. One instance
 * serves every tenant and repository, keeping a per-scope {@link LuceneSearcher} so a repository's loaded index and
 * its TTL refresh survive across requests - a caller resolves this once (a final field) and calls {@link #over} per
 * request. The searcher itself does the store reads, so a fresh per-request scoped store is fine.
 *
 * <p>The per-scope cache is <strong>bounded</strong>, never an unbounded map: each entry holds a whole
 * file-backed index over the node's segment cache, so a fleet that has ever searched thousands of repositories would
 * otherwise grow its heap to the sum of every index ever loaded. Instead the cache is a size- and count-weighted LRU
 * with an idle-TTL: an entry untouched for longer than the idle window is dropped, and when the resident set exceeds
 * either the scope-count cap or the resident-heap-byte cap the least-recently-used scopes are evicted until it fits.
 * An evicted searcher is {@link LuceneSearcher#close closed} so its buffers are freed at once rather than left for the
 * garbage collector. A re-queried scope simply reloads its index from the store snapshot on the next {@link #over} -
 * the index is derived data, and the reload is the same TTL refresh a cold scope already does.
 */
public final class LuceneSearchQueryProvider implements SearchQueryProvider {

    /** How long a loaded index serves before the reader re-checks the manifest generation; short, so a new snapshot
     *  becomes visible within a query or two of a sweep without re-downloading an unchanged one. */
    private static final Duration TTL = Duration.ofSeconds(30);

    /** The most repository scopes kept resident at once - a hard ceiling on the number of loaded indexes so the heap
     *  cannot grow with the number of repositories ever searched. */
    private static final int MAX_RESIDENT = 128;

    /** The most resident index heap kept at once - the size half of the weighting, so a few very large indexes are
     *  bounded even when the scope count is not near its cap. */
    private static final long MAX_RESIDENT_BYTES = 512L << 20;

    /** How long a scope may go unqueried before its loaded index is dropped, so an idle repository does not hold heap
     *  indefinitely between the rare searches it sees. */
    private static final Duration IDLE_TTL = Duration.ofMinutes(15);

    private final Duration ttl;
    private final int maxResident;
    private final long maxResidentBytes;
    private final long idleTtlNanos;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** One resident scope: its searcher and the last time it was handed to a request, so the LRU can order and age it. */
    private static final class Entry {

        private final LuceneSearcher searcher;
        private volatile long accessNanos;

        private Entry(LuceneSearcher searcher, long accessNanos) {
            this.searcher = searcher;
            this.accessNanos = accessNanos;
        }
    }

    /** The discovered constructor: the default 30-second refresh window and the default cache bounds. */
    public LuceneSearchQueryProvider() {
        this(TTL, MAX_RESIDENT, MAX_RESIDENT_BYTES, IDLE_TTL);
    }

    /** With an explicit refresh window - {@link Duration#ZERO} makes every query re-check the manifest generation,
     *  which a test uses to observe a new generation the moment a sweep publishes it - and the default cache bounds. */
    public LuceneSearchQueryProvider(Duration ttl) {
        this(ttl, MAX_RESIDENT, MAX_RESIDENT_BYTES, IDLE_TTL);
    }

    /** With explicit cache bounds, so a scale test can drive eviction at a small size without loading thousands of
     *  indexes. */
    public LuceneSearchQueryProvider(Duration ttl, int maxResident, long maxResidentBytes, Duration idleTtl) {
        this.ttl = ttl;
        this.maxResident = Math.max(1, maxResident);
        this.maxResidentBytes = maxResidentBytes;
        this.idleTtlNanos = idleTtl.toNanos();
    }

    @Override
    public SearchQuery over(ArtifactStore store, String scope) {
        long now = System.nanoTime();
        Entry entry = entries.computeIfAbsent(scope, _ -> new Entry(new LuceneSearcher(ttl), now));
        entry.accessNanos = now;
        evict(scope, now);
        LuceneSearcher searcher = entry.searcher;
        return new SearchQuery() {
            @Override
            public Optional<Hits> search(String query, String cursor, int limit) throws IOException {
                return searcher.search(store, query, cursor, limit);
            }

            @Override
            public Optional<List<LicenseFacet>> licenses() throws IOException {
                return searcher.licenses(store);
            }
        };
    }

    /** The number of scopes currently resident - the observable the cache bound is asserted against; never grows past
     *  {@code maxResident} once a sweep of {@link #over} calls has driven eviction. */
    public int residentScopes() {
        return entries.size();
    }

    /**
     * Drop resident scopes back within the bounds after a fresh {@link #over}: first every entry idle past the idle-TTL
     * (the scope just handed back is exempt, it was touched now), then - if the resident set still exceeds the scope
     * count cap or the resident-heap-byte cap - the least-recently-used scopes until it fits, never evicting the scope
     * just returned. Serialised so two concurrent requests never double-close a searcher; the eviction scan is over at
     * most {@code maxResident} small entries, far cheaper than the query it precedes.
     */
    private synchronized void evict(String current, long nowNanos) {
        Iterator<Map.Entry<String, Entry>> idle = entries.entrySet().iterator();
        while (idle.hasNext()) {
            Map.Entry<String, Entry> resident = idle.next();
            if (!resident.getKey().equals(current) && nowNanos - resident.getValue().accessNanos > idleTtlNanos) {
                idle.remove();
                resident.getValue().searcher.close();
            }
        }
        long bytes = residentBytes();
        if (entries.size() <= maxResident && bytes <= maxResidentBytes) {
            return;
        }
        List<Map.Entry<String, Entry>> byAge = new ArrayList<>(entries.entrySet());
        byAge.sort(Comparator.comparingLong(resident -> resident.getValue().accessNanos));   // oldest first
        int count = entries.size();
        for (Map.Entry<String, Entry> resident : byAge) {
            if (count <= maxResident && bytes <= maxResidentBytes) {
                break;
            }
            if (resident.getKey().equals(current)) {
                continue;                                       // never evict the scope we just handed back
            }
            long freed = resident.getValue().searcher.residentBytes();
            entries.remove(resident.getKey());
            resident.getValue().searcher.close();
            bytes -= freed;
            count--;
        }
    }

    /** The summed resident heap of every loaded scope, for the byte-weighted half of the LRU. */
    private long residentBytes() {
        long total = 0;
        for (Entry entry : entries.values()) {
            total += entry.searcher.residentBytes();
        }
        return total;
    }
}
