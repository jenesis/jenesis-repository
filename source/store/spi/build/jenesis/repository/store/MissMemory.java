package build.jenesis.repository.store;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A node's memory of what it looked for and did not find: a bounded, expiring set of store keys a serve probed and
 * found absent, so the same probe is answered from memory rather than from the store for {@code jenreg.cache.miss-ttl}.
 * The read it spares is the pointer read at the head of every download - a build tool probing a version range, a
 * missing snapshot or an optional classifier across a group's members asks the same question of the same
 * repositories many times in a row, and each asked the store until now.
 *
 * <h2>What it remembers, and what it never does</h2>
 * Only a clean absence is remembered: a pointer read that answered empty. A pointer that exists, whatever its
 * state, is never an entry - a hit is served and forgotten, a withheld path is a pointer with a flag, and neither is
 * a miss. A read that failed is not remembered either, since an error is not an answer.
 *
 * <h2>Write-through on the node, a ttl across nodes</h2>
 * Every write and delete through a {@link MissMemoStore} forgets its key here, so the node that published a path
 * serves it at once whatever it remembered, and a hold released here is served here. Another node learns of a
 * publish only when its entry expires, which is what the ttl bounds: a fresh artifact may answer 404 from a
 * second node for up to the ttl, and the default is short for that reason. A deployment that cannot afford the
 * window turns it off with {@code 0}, and every probe is the store's again.
 *
 * <h2>Bounded twice</h2>
 * The memory holds at most {@value #MAX_ENTRIES} keys and none past its ttl, so a client probing a million names
 * that do not exist fills it and evicts the oldest rather than the heap; an entry costs the key's characters and a
 * timestamp. It is node-local by construction, one memory per process shared by every store the node opens, keyed
 * by the store's {@linkplain ArtifactStore#identity() identity} and the key within it, so two tenants' stores
 * never answer for each other and the same store reached through two objects shares one memory. It is on the
 * registry {@code POST /api/admin/caches/clear} drops.
 */
public final class MissMemory {

    /** The setting the ttl is read from ({@code jenreg.cache.miss-ttl}). */
    public static final String TTL_SETTING = "cache.miss-ttl";

    /** The default as an operator writes it; {@link #DEFAULT_TTL} parses this. Ten seconds: long enough to absorb
     *  a build tool's burst of probes for a name that is not there, short enough that a publish on another node
     *  is served everywhere before anyone retries. */
    public static final String DEFAULT_TTL_TEXT = "PT10S";

    public static final Duration DEFAULT_TTL = Duration.parse(DEFAULT_TTL_TEXT);

    /** The most keys the memory holds, whatever is probed: past it the oldest entries go. */
    static final long MAX_ENTRIES = 100_000;

    private static final Object NODE_LOCK = new Object();
    private static MissMemory node;

    private final Duration ttl;
    private final Clock clock;
    private final Cache<String, Instant> misses;
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong spared = new AtomicLong();

    /** A memory that remembers a miss for {@code ttl}; {@code Duration.ZERO} remembers nothing. */
    public MissMemory(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    /** As above, judging an entry's age by {@code clock} - the seam a test advances to prove expiry without
     *  sleeping. The bound's own expiry rides the JVM clock and only ever trims sooner than the memory would. */
    public MissMemory(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("a miss ttl is zero (off) or positive: " + ttl);
        }
        Duration lifetime = ttl.isZero() ? Duration.ofNanos(1) : ttl;
        this.misses = Caffeine.newBuilder().expireAfterWrite(lifetime).maximumSize(MAX_ENTRIES).build();
    }

    /** The one memory of this process, built from {@link #TTL_SETTING} on first use. */
    public static MissMemory node() {
        synchronized (NODE_LOCK) {
            if (node == null) {
                node = new MissMemory(configuredTtl());
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

    public Duration ttl() {
        return ttl;
    }

    /** Whether {@code key} in {@code store} was looked for and found absent within the ttl - the answer a serve
     *  takes in place of the store's, counted as a read spared. */
    public boolean remembered(ArtifactStore store, String key) {
        if (ttl.isZero()) {
            return false;
        }
        Instant at = misses.getIfPresent(key(store, key));
        if (at == null) {
            return false;
        }
        if (!clock.instant().isBefore(at.plus(ttl))) {
            misses.invalidate(key(store, key));
            return false;
        }
        spared.incrementAndGet();
        return true;
    }

    /** Remember that {@code key} in {@code store} was just read and found absent. */
    public void remember(ArtifactStore store, String key) {
        if (ttl.isZero()) {
            return;
        }
        misses.put(key(store, key), clock.instant());
        recorded.incrementAndGet();
    }

    /** Forget {@code key} in {@code store} - what every write and delete through a {@link MissMemoStore} does, so
     *  the node that wrote serves what it wrote at once. */
    public void forget(ArtifactStore store, String key) {
        if (ttl.isZero()) {
            return;
        }
        misses.invalidate(key(store, key));
    }

    /** Drop every entry and answer how many went. */
    public int clear() {
        misses.cleanUp();
        int dropped = (int) Math.min(Integer.MAX_VALUE, misses.estimatedSize());
        misses.invalidateAll();
        return dropped;
    }

    /** Entries held now, after expired ones are trimmed. */
    public long size() {
        misses.cleanUp();
        return misses.estimatedSize();
    }

    /** Misses recorded since the memory was built. */
    public long recorded() {
        return recorded.get();
    }

    /** Probes answered from memory instead of the store since the memory was built. */
    public long spared() {
        return spared.get();
    }

    private static String key(ArtifactStore store, String key) {
        return ReadMemo.underlying(store).identity() + "\u0000" + key;
    }
}
