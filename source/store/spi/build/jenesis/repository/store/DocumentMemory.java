package build.jenesis.repository.store;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A node's memory of the small documents it serves again and again: the stored listings - a packument, a Simple
 * page, a {@code maven-metadata.xml}, a {@code Packages} file, a tag list - that every build starting at once asks
 * for, kept in memory for {@code jenrepo.cache.document-ttl} so a burst of uncached builds costs the store one read
 * per document rather than one per build. It is the positive half of what {@link MissMemory} is the negative half
 * of, and it rides the same {@link NodeMemoStore}: the store's stream face hands a remembered document back as
 * bytes, and every write and delete through the store forgets the key it touched.
 *
 * <h2>What it holds</h2>
 * Only the {@linkplain StoredListing#ROOT listing} family, and only documents up to {@value #ENTRY_CAP} bytes: a
 * listing is written whole by the write path and read whole by a client, so a copy is exact, while an artifact's
 * bytes are never a candidate and a listing past the cap streams from the store. Never a pointer,
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

    /** The setting the ttl is read from ({@code jenrepo.cache.document-ttl}). */
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
    /** When each recently forgotten key was forgotten, by {@link #sequence}: a read that began before a key's last
     *  forget may not remember what it read, since a write landed in between. Held twice the ttl and bounded, as the
     *  miss memory holds its own. */
    private final Cache<String, Long> forgotten;
    private final AtomicLong sequence = new AtomicLong();
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
        this.forgotten = Caffeine.newBuilder().expireAfterWrite(lifetime.multipliedBy(2))
                .maximumSize(MissMemory.MAX_ENTRIES).build();
    }

    /** The one memory of this process, built from {@link #TTL_SETTING} on first use. Process-wide, as the node's
     *  other caches are, because it is the node's memory and is cleared and reported as the node's: every entry is
     *  keyed by the identity of the store it was read from, so two deployments over different stores never answer
     *  from each other's entries, and what they share is the ttl the first one read. */
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

    /** Where the memory's forgets stand: taken before a read whose document may be remembered, and handed to
     *  {@link #put}. */
    public long mark() {
        return sequence.get();
    }

    /**
     * Remember {@code bytes}, read by a read begun at {@code mark}, as the document at {@code key} in {@code store} -
     * unless the key was forgotten since, since a write that landed between the read and this call would otherwise
     * have its listing served as it stood before it for the whole ttl. A forget racing the put itself is caught by
     * asking again once the entry is in. A document past {@link #ENTRY_CAP} or outside the listing family is not kept.
     */
    public void put(ArtifactStore store, String key, byte[] bytes, long mark) {
        if (ttl.isZero() || !covers(key) || bytes.length > ENTRY_CAP) {
            return;
        }
        String id = id(store, key);
        if (forgottenSince(id, mark)) {
            return;
        }
        documents.put(id, new Entry(bytes, clock.instant()));
        if (forgottenSince(id, mark)) {
            documents.invalidate(id);
        }
    }

    /** Forget {@code key} in {@code store} - what every write and delete through a {@link NodeMemoStore} does. */
    public void forget(ArtifactStore store, String key) {
        if (ttl.isZero() || !covers(key)) {
            return;
        }
        String id = id(store, key);
        forgotten.put(id, sequence.incrementAndGet());
        documents.invalidate(id);
    }

    private boolean forgottenSince(String id, long mark) {
        Long forgot = forgotten.getIfPresent(id);
        return forgot != null && forgot > mark;
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
