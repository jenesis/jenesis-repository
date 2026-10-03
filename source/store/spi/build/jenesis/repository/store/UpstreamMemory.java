package build.jenesis.repository.store;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A node's memory of the mutable documents a proxy relays from its upstream - a {@code maven-metadata.xml} a
 * repository holds none of - kept for {@code jenrepo.cache.upstream-ttl} so one document costs the upstream one fetch
 * per ttl rather than one per client. It is {@link DocumentMemory}'s counterpart for what a repository relays rather
 * than stores: a client resolving against such a document already tolerates a day's staleness by its own update
 * policy, which is what the default spends.
 *
 * <h2>What it holds</h2>
 * The body of an upstream answer that was a document - a {@code 200} - up to {@value #ENTRY_CAP} bytes, with the
 * headers a client reads it by: its {@code Content-Type} and the validators it revalidates against. A larger one is
 * relayed uncached. Never a refusal or a miss: an upstream that could not be reached or answered an error is asked
 * again on the next request, so a remembered document is always one the upstream served. Keyed by the
 * {@linkplain ArtifactStore#identity() identity} of the repository the document was relayed for and the upstream
 * URL, so a document one repository fetched with its own upstream credentials never answers another.
 *
 * <h2>Bounded by bytes</h2>
 * The memory weighs its entries and holds at most {@value #MAX_BYTES} bytes, evicting the least recently used past
 * it, and none past the ttl. Node-local, and on the registry {@code POST /api/admin/caches/clear} drops.
 */
public final class UpstreamMemory {

    /** The setting the ttl is read from ({@code jenrepo.cache.upstream-ttl}). */
    public static final String TTL_SETTING = "cache.upstream-ttl";

    /** The default as an operator writes it; {@link #DEFAULT_TTL} parses this. Six hours: shorter than the day a
     *  Maven client waits by default before it asks for a document again, so a release published upstream reaches a
     *  client here within the same working day, while a burst of builds costs the upstream one fetch. */
    public static final String DEFAULT_TTL_TEXT = "PT6H";

    public static final Duration DEFAULT_TTL = Duration.parse(DEFAULT_TTL_TEXT);

    /** The most bytes the memory holds across every document. */
    static final long MAX_BYTES = 64L << 20;

    /** The largest document remembered; a larger one is relayed uncached. */
    public static final int ENTRY_CAP = 1 << 20;

    private static final Object NODE_LOCK = new Object();
    private static UpstreamMemory node;

    private final Duration ttl;
    private final Clock clock;
    private final Cache<String, Entry> documents;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** The headers a remembered document keeps: what a client reads it as, and what it revalidates against. */
    public static final List<String> HEADERS = List.of("Content-Type", "ETag", "Last-Modified");

    /** A remembered document: its body and those of its {@link #HEADERS} the upstream sent. */
    public record Remembered(byte[] body, Map<String, String> headers) {

        public Remembered {
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(headers);
        }
    }

    private record Entry(Remembered document, Instant at) {
    }

    /** A memory that keeps a document for {@code ttl}; {@code Duration.ZERO} keeps nothing. */
    public UpstreamMemory(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    /** As above, judging an entry's age by {@code clock} - the seam a test advances to prove expiry without
     *  sleeping; the bound's own expiry rides the JVM clock and only ever trims sooner. */
    public UpstreamMemory(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("an upstream document ttl is zero (off) or positive: " + ttl);
        }
        this.documents = Caffeine.newBuilder()
                .expireAfterWrite(ttl.isZero() ? Duration.ofNanos(1) : ttl)
                .maximumWeight(MAX_BYTES)
                .weigher((String key, Entry entry) -> entry.document().body().length + key.length())
                .build();
    }

    /** The one memory of this process, built from {@link #TTL_SETTING} on first use - process-wide, as the node's
     *  other memories are, and keyed by repository so two deployments over different stores never share an entry. */
    public static UpstreamMemory node() {
        synchronized (NODE_LOCK) {
            if (node == null) {
                node = new UpstreamMemory(ttl(Features.settings().apply(TTL_SETTING)));
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

    /** {@code setting} as a duration, in the forms {@link StoreCache#ttl} accepts; empty is {@link #DEFAULT_TTL}. */
    public static Duration ttl(String setting) {
        return StoreCache.duration(setting, TTL_SETTING, DEFAULT_TTL);
    }

    public Duration ttl() {
        return ttl;
    }

    /** What the upstream served at {@code url} for {@code repository}, while the entry is live. */
    public Optional<Remembered> get(ArtifactStore repository, URI url) {
        if (ttl.isZero()) {
            return Optional.empty();
        }
        String id = id(repository, url);
        Entry entry = documents.getIfPresent(id);
        if (entry == null || !clock.instant().isBefore(entry.at().plus(ttl))) {
            if (entry != null) {
                documents.invalidate(id);
            }
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry.document());
    }

    /** Remember {@code body} as what the upstream served at {@code url} for {@code repository}, with its
     *  {@link #HEADERS} as {@code header} answers them; a document past {@link #ENTRY_CAP} is not kept. */
    public void put(ArtifactStore repository, URI url, byte[] body, UnaryOperator<String> header) {
        if (ttl.isZero() || body.length > ENTRY_CAP) {
            return;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : HEADERS) {
            String value = header.apply(name);
            if (value != null) {
                kept.put(name, value);
            }
        }
        documents.put(id(repository, url), new Entry(new Remembered(body, kept), clock.instant()));
    }

    /** Drop every document and answer how many went. */
    public int clear() {
        documents.cleanUp();
        int dropped = (int) Math.min(Integer.MAX_VALUE, documents.estimatedSize());
        documents.invalidateAll();
        return dropped;
    }

    /** Bytes held now, the sum of every live document's length. */
    public long bytes() {
        documents.cleanUp();
        long total = 0;
        for (Entry entry : documents.asMap().values()) {
            total += entry.document().body().length;
        }
        return total;
    }

    /** Relays answered from memory since the memory was built. */
    public long hits() {
        return hits.get();
    }

    /** Relays that went to the upstream since the memory was built. */
    public long misses() {
        return misses.get();
    }

    private static String id(ArtifactStore repository, URI url) {
        return ReadMemo.underlying(repository).identity() + "\u0000" + url;
    }
}
