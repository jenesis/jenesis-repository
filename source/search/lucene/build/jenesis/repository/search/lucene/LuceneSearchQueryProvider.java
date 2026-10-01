package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Binds the Lucene search index to a repository's scoped store. One instance serves every tenant and repository,
 * keeping a {@link LuceneSearcher} per scope so a loaded index and its refresh window survive across requests; a caller
 * resolves this once and calls {@link #over} per request.
 *
 * <p>The per-scope cache is bounded, since each entry holds a whole index: a count- and size-weighted LRU with an idle
 * TTL. An entry idle past the window is dropped, and past the scope-count or resident-byte cap the least-recently-used
 * scopes are evicted and {@link LuceneSearcher#close closed}. A re-queried scope reloads its index from the store.
 */
public final class LuceneSearchQueryProvider implements SearchQueryProvider {

    /** How long a loaded index serves before the reader re-checks the manifest generation: short, so a new snapshot is
     *  visible within a query or two without re-downloading an unchanged one. */
    private static final Duration TTL = Duration.ofSeconds(30);

    /** The most repository scopes kept resident, so the heap cannot grow with the number of repositories ever
     *  searched. */
    private static final int MAX_RESIDENT = 128;

    /** The most resident index bytes kept, so a few very large indexes are bounded too. */
    private static final long MAX_RESIDENT_BYTES = 512L << 20;

    /** How long a scope may go unqueried before its loaded index is dropped. */
    private static final Duration IDLE_TTL = Duration.ofMinutes(15);

    private final Duration ttl;
    private final int maxResident;
    private final long maxResidentBytes;
    private final long idleTtlNanos;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** One resident scope: its searcher and when it was last handed out. */
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

    /** With an explicit refresh window ({@link Duration#ZERO} re-checks the manifest on every query) and the default
     *  bounds. */
    public LuceneSearchQueryProvider(Duration ttl) {
        this(ttl, MAX_RESIDENT, MAX_RESIDENT_BYTES, IDLE_TTL);
    }

    /** With explicit cache bounds. */
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
        };
    }

    /** The number of scopes resident, never past {@code maxResident} once eviction has run. */
    public int residentScopes() {
        return entries.size();
    }

    /** Bring the resident scopes back within bounds after an {@link #over}: every entry idle past the idle TTL, then
     *  the least-recently-used scopes until both caps hold, never the scope just returned. Serialised, so no searcher
     *  is closed twice. */
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
