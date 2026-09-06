package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.Signals;

/**
 * A read-through, write-through, time-bounded cache of the store's small versioned documents - the one mechanism
 * every point read the product repeats goes through: a credential's grants, a settings document, a rate-limit
 * ceiling, a quota counter. On an object store a point read is a round trip and a bill, and the product's model
 * (a repository is read far more than written, and a read has to reach the blob store anyway) holds only while a
 * request pays for nothing twice; three uncached reads authorised every download before this existed, a third of
 * the read path's operations.
 *
 * <p><b>Read-through</b>: a miss reads the store and keeps the answer - an absent key included, so a storm of
 * requests with an unprovisioned key does not re-ask the store per request. <b>Write-through</b>: a write or delete
 * this node makes through the cache drops the entry as the store takes it, so this node's next read sees what it
 * wrote; the entry is dropped rather than overwritten, because a plain {@link ArtifactStore#write} leaves the store
 * to mint the version token and a cache must never hold a token the store did not issue. On a single node the cache
 * is therefore exact and the time bound never matters. <b>Time-bounded</b>: across nodes an entry may be stale by
 * up to the ttl - another node's grant, revocation or setting shows here within {@code jenreg.cache.ttl}, five
 * minutes by default - and a deployment that needs a revoked key to stop within seconds on every node turns that
 * dial down; {@code 0} switches caching off and every read is the store's.
 *
 * <p>Every cache {@linkplain #caches() registers itself}, so one call {@linkplain #clearAll() clears them all} on
 * the node it reaches - the admin endpoint, the CLI and the console button behind it address a node, never the
 * fleet - and every cache reports its hits, misses and entries on the observability report, because a cache whose
 * effect is not measured is a cache nobody trusts.
 */
public final class StoreCache {

    /** The setting: an ISO-8601 or suffixed duration ({@code PT5M}, {@code 30s}); {@code 0} switches caching off. */
    public static final String TTL_SETTING = "cache.ttl";

    /** Five minutes: what another node's write may lag by, and the bound a deployment turns down when it must. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private static final Set<StoreCache> CACHES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** The shared caches by store identity and name - see {@link #of}. Weak, so a cache lives as long as something
     *  holds it and a test's store does not pin one for the life of the JVM. */
    private static final Map<String, WeakReference<StoreCache>> SHARED = new HashMap<>();

    private record Entry(Optional<ArtifactStore.Versioned> value, long expiresAtNanos) {
    }

    private record Listing(List<String> names, long expiresAtNanos) {
    }

    private final String name;
    /** {@code jenreg.cache.<name>}, validated against the signal grammar when the cache is made - a name the report
     *  would refuse is refused here, at boot, rather than dropping every cache's counters from the report. */
    private final String prefix;
    private final ArtifactStore store;
    private final Duration ttl;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** A cache named {@code name} on the observability report, over {@code store}, bounded by {@code ttl}
     *  ({@link Duration#ZERO} means no caching at all). */
    public StoreCache(String name, ArtifactStore store, Duration ttl) {
        this.name = Objects.requireNonNull(name, "name");
        this.prefix = Signals.name("cache", name);
        this.store = Objects.requireNonNull(store, "store");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("a cache ttl is zero (off) or positive: " + ttl);
        }
        CACHES.add(this);
    }

    /**
     * The one cache named {@code name} over the store {@code store} {@linkplain ArtifactStore#identity() is} in this
     * process, created on first use with {@code ttl}. Two holders of the same store - the repository's authorisation
     * and the cache dispatcher's, a console screen and the pass behind it - reach the same entries, so a write one
     * of them makes through the cache is seen by the other at once: write-through holds for the node, not for the
     * object that happened to make the write. The ttl is the first holder's; a later holder asking for another gets
     * the shared cache as it is.
     */
    public static StoreCache of(String name, ArtifactStore store, Duration ttl) {
        String key = store.identity() + "\u0000" + name;
        synchronized (SHARED) {
            WeakReference<StoreCache> reference = SHARED.get(key);
            StoreCache shared = reference == null ? null : reference.get();
            if (shared == null) {
                shared = new StoreCache(name, store, ttl);
                SHARED.values().removeIf(each -> each.get() == null);
                SHARED.put(key, new WeakReference<>(shared));
            }
            return shared;
        }
    }

    /** {@link #TTL_SETTING} as the deployment set it, or {@link #DEFAULT_TTL}; a value that is not a duration is
     *  refused at boot rather than read as a default the operator did not choose. */
    public static Duration configuredTtl() {
        return ttl(Features.settings().apply(TTL_SETTING));
    }

    /** {@code setting} as a duration: {@code 0} is off, an ISO-8601 duration or a suffixed one ({@code 500ms},
     *  {@code 30s}, {@code 5m}, {@code 1h}) is itself, empty is {@link #DEFAULT_TTL}, anything else is refused. */
    public static Duration ttl(String setting) {
        if (setting == null || setting.isBlank()) {
            return DEFAULT_TTL;
        }
        String value = setting.trim();
        if (value.equals("0")) {
            return Duration.ZERO;
        }
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Duration.parse(value.toUpperCase(Locale.ROOT));
            }
            Matcher suffixed = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)").matcher(value);
            if (suffixed.matches()) {
                long amount = Long.parseLong(suffixed.group(1));
                return switch (suffixed.group(2)) {
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default -> Duration.ofDays(amount);
                };
            }
        } catch (DateTimeParseException | NumberFormatException _) {
            // fall through to the refusal below
        }
        throw new IllegalArgumentException(Features.key(TTL_SETTING) + "=" + setting + " is not a duration; accepted: "
                + "0 (off), an ISO-8601 duration (PT5M, PT30S) or a suffixed one (500ms, 30s, 5m, 1h)");
    }

    public String name() {
        return name;
    }

    public Duration ttl() {
        return ttl;
    }

    /** The document at {@code key} - from the cache while its entry is live, else from the store, kept for the ttl. */
    public Optional<ArtifactStore.Versioned> readVersioned(String key) throws IOException {
        if (ttl.isZero()) {
            misses.incrementAndGet();
            return store.readVersioned(key);
        }
        long now = System.nanoTime();
        Entry entry = entries.get(key);
        if (entry != null && entry.expiresAtNanos() - now > 0) {
            hits.incrementAndGet();
            return entry.value();
        }
        misses.incrementAndGet();
        Optional<ArtifactStore.Versioned> value = store.readVersioned(key);
        entries.put(key, new Entry(value, now + ttl.toNanos()));
        return value;
    }

    /** The immediate children of {@code prefix} - from the cache while the listing is live, else from the store, kept
     *  for the ttl. A listing is a write-class call on every object store, so a gauge or a heartbeat that asks for
     *  the tenant set every minute asks the store once per ttl instead. */
    public List<String> list(String prefix) throws IOException {
        if (ttl.isZero()) {
            misses.incrementAndGet();
            return store.list(prefix);
        }
        long now = System.nanoTime();
        Listing listing = listings.get(prefix);
        if (listing != null && listing.expiresAtNanos() - now > 0) {
            hits.incrementAndGet();
            return listing.names();
        }
        misses.incrementAndGet();
        List<String> names = List.copyOf(store.list(prefix));
        listings.put(prefix, new Listing(names, now + ttl.toNanos()));
        return names;
    }

    /** Drop the cached listing of {@code prefix}: for a child this node created or removed past this cache. */
    public void invalidateListing(String prefix) {
        listings.remove(prefix);
    }

    /** Write {@code content} at {@code key} and drop the entry, so this node's next read sees the store's answer. */
    public void write(String key, byte[] content) throws IOException {
        store.write(key, new ByteArrayInputStream(content));
        entries.remove(key);
    }

    /** {@link ArtifactStore#writeVersioned}, dropping the entry whether or not the compare-and-set landed - a lost
     *  one means the store holds something newer than the entry. */
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        try {
            return store.writeVersioned(key, content, expected);
        } finally {
            entries.remove(key);
        }
    }

    /** Delete {@code key} and drop its entry. */
    public void delete(String key) throws IOException {
        try {
            store.delete(key);
        } finally {
            entries.remove(key);
        }
    }

    /** Drop the entry at {@code key}: for a write that went past this cache to the store. */
    public void invalidate(String key) {
        entries.remove(key);
    }

    /** Drop every entry; answers how many there were. */
    public int clear() {
        int size = entries.size() + listings.size();
        entries.clear();
        listings.clear();
        return size;
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public int size() {
        return entries.size() + listings.size();
    }

    /** The counters this cache reports: hits, misses and live entries, under {@code jenreg.cache.<name>}. */
    public List<Metric> metrics() {
        return List.of(
                Metric.counter(prefix + ".hits", "Reads of the " + name + " cache answered without a store round trip.",
                        hits.get(), "reads"),
                Metric.counter(prefix + ".misses", "Reads of the " + name + " cache that went to the store - every read "
                        + "when the ttl is 0.", misses.get(), "reads"),
                Metric.gauge(prefix + ".entries", "Documents and listings the " + name + " cache holds.", size(), "entries"));
    }

    /** Every cache alive in this process, by registration; a cache is forgotten when nothing holds it. */
    public static List<StoreCache> caches() {
        synchronized (CACHES) {
            return List.copyOf(CACHES);
        }
    }

    /** Clear every cache in this process - the node-local clear the admin surfaces reach - and answer how many
     *  entries went. */
    public static int clearAll() {
        int dropped = 0;
        for (StoreCache cache : caches()) {
            dropped += cache.clear();
        }
        return dropped;
    }

    @Override
    public String toString() {
        return "StoreCache[" + name + ", ttl=" + ttl + ", entries=" + entries.size() + "]";
    }
}
