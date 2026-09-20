package build.jenesis.repository.store;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A node's memory of the small documents it serves again and again: the stored listings - a packument, a Simple
 * page, a {@code maven-metadata.xml}, a {@code Packages} file, a tag list - that every build starting at once asks
 * for, kept in memory for {@code jenreg.cache.document-ttl} so a burst of uncached builds costs the store one read
 * per document rather than one per build. It is the positive half of what {@link MissMemory} is the negative half
 * of, and it rides the same {@link NodeMemoStore}: the store's stream face hands a remembered document back as
 * bytes, and every write and delete through the store forgets the key it touched.
 *
 * <h2>What it holds</h2>
 * Only the {@linkplain StoredListing#ROOT listing} family, and only documents up to {@value #ENTRY_CAP} bytes: a
 * listing is written whole by the write path and read whole by a client, so a copy is exact, while an artifact's
 * bytes are never a candidate and a listing past the cap streams from the store as it always did. Never a pointer,
 * deliberately: a pointer carries the hold flag a release or a quarantine flips, and a hold must land on every
 * node at once, not once a copy expires. A listing entry a hold removes shows on another node for at most the
 * ttl, which is what the default trades for the reads it spares.
 *
 * <h2>Bounded by bytes</h2>
 * The memory weighs its entries and holds at most {@value #MAX_BYTES} bytes, evicting the least recently used
 * past it, and none past the ttl. Node-local, keyed by the store's {@linkplain ArtifactStore#identity() identity}
 * and the key within it, and on the registry {@code POST /api/admin/caches/clear} drops.
 */
public final class DocumentMemory {

    /** The setting the ttl is read from ({@code jenreg.cache.document-ttl}). */
    public static final String TTL_SETTING = "cache.document-ttl";

    /** The default as an operator writes it; {@link #DEFAULT_TTL} parses this. Thirty seconds: the length of a
     *  burst of builds resolving the same metadata, and the longest another node's publish may take to show in a
     *  listing served here. Longer than the miss memory's, since a stale listing costs a client a version it
     *  would see on the next resolve, where a stale absence costs it the artifact. */
    public static final String DEFAULT_TTL_TEXT = "PT30S";

    public static final Duration DEFAULT_TTL = Duration.parse(DEFAULT_TTL_TEXT);

    /** The most bytes the memory holds across every document. */
    static final long MAX_BYTES = 64L << 20;

    /** The largest document remembered; a larger one streams from the store, uncached. */
    static final int ENTRY_CAP = 1 << 20;

    private static final Object NODE_LOCK = new Object();
    private static DocumentMemory node;

    private final Duration ttl;
    private final Clock clock;
    private final Cache<String, Entry> documents;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private record Entry(byte[] bytes, Instant at) {
    }

    /** A memory that keeps a document for {@code ttl}; {@code Duration.ZERO} keeps nothing. */
    public DocumentMemory(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    /** As above, judging an entry's age by {@code clock} - the seam a test advances to prove expiry without
     *  sleeping; the bound's own expiry rides the JVM clock and only ever trims sooner. */
    public DocumentMemory(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("a document ttl is zero (off) or positive: " + ttl);
        }
        Duration lifetime = ttl.isZero() ? Duration.ofNanos(1) : ttl;
        this.documents = Caffeine.newBuilder()
                .expireAfterWrite(lifetime)
                .maximumWeight(MAX_BYTES)
                .weigher((String key, Entry entry) -> entry.bytes().length + key.length())
                .build();
    }

    /** The one memory of this process, built from {@link #TTL_SETTING} on first use. */
    public static DocumentMemory node() {
        synchronized (NODE_LOCK) {
            if (node == null) {
                node = new DocumentMemory(configuredTtl());
            }
            return node;
        }
    }

    /** Drop the process's memory so the next {@link #node()} reads the setting again - for a suite that pins the
     *  ttl and clears it; a deployment never calls this. */
    public static void reset() {
        synchronized (NODE_LOCK) {
            node = null;
        }
    }

    /** {@link #TTL_SETTING} as the deployment set it, or {@link #DEFAULT_TTL}; a value that is not a duration is
     *  refused at boot rather than read as a default the operator did not choose. */
    public static Duration configuredTtl() {
        return ttl(Features.settings().apply(TTL_SETTING));
    }

    /** {@code setting} as a duration, in the forms {@link StoreCache#ttl} accepts; empty is {@link #DEFAULT_TTL}. */
    public static Duration ttl(String setting) {
        return StoreCache.duration(setting, TTL_SETTING, DEFAULT_TTL);
    }

    /** Whether {@code key} names a document this memory would hold: one of the stored listings. */
    public static boolean covers(String key) {
        return key != null && key.startsWith(StoredListing.ROOT);
    }

    public Duration ttl() {
        return ttl;
    }

    /** The remembered bytes of {@code key} in {@code store} while the entry is live, else empty. */
    public Optional<byte[]> get(ArtifactStore store, String key) {
        if (ttl.isZero() || !covers(key)) {
            return Optional.empty();
        }
        String id = id(store, key);
        Entry entry = documents.getIfPresent(id);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.at().plus(ttl))) {
            documents.invalidate(id);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry.bytes());
    }

    /** Remember {@code bytes} as the document at {@code key} in {@code store}; a document past {@link #ENTRY_CAP}
     *  or outside the listing family is not kept. */
    public void put(ArtifactStore store, String key, byte[] bytes) {
        if (ttl.isZero() || !covers(key) || bytes.length > ENTRY_CAP) {
            return;
        }
        documents.put(id(store, key), new Entry(bytes, clock.instant()));
    }

    /** Forget {@code key} in {@code store} - what every write and delete through a {@link NodeMemoStore} does. */
    public void forget(ArtifactStore store, String key) {
        if (ttl.isZero() || !covers(key)) {
            return;
        }
        documents.invalidate(id(store, key));
    }

    /** Drop every document and answer how many went. */
    public int clear() {
        documents.cleanUp();
        int dropped = (int) Math.min(Integer.MAX_VALUE, documents.estimatedSize());
        documents.invalidateAll();
        return dropped;
    }

    /** Documents held now, after expired ones are trimmed. */
    public long size() {
        documents.cleanUp();
        return documents.estimatedSize();
    }

    /** Bytes held now, the sum of every live document's length. */
    public long bytes() {
        documents.cleanUp();
        long total = 0;
        for (Entry entry : documents.asMap().values()) {
            total += entry.bytes().length;
        }
        return total;
    }

    /** Document reads answered from memory since the memory was built. */
    public long hits() {
        return hits.get();
    }

    /** Document reads that went to the store since the memory was built. */
    public long misses() {
        return misses.get();
    }

    private static String id(ArtifactStore store, String key) {
        return ReadMemo.underlying(store).identity() + "\u0000" + key;
    }
}
