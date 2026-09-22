package build.jenesis.repository.store.metering;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * An {@link ArtifactStore} decorator that times each store operation as {@code jenreg.store.operations}, tagged by
 * the operation ({@code op}), the configured backend ({@code backend}: filesystem, s3, azure, ...) and the
 * {@code outcome} ({@code ok}/{@code error}) - so a deployment sees per-backend store latency the same way the
 * quota decorator meters bytes. It is wired only in the distribution, and only when a {@link MeterRegistry} is
 * present, so the free serving path stays inert (no metrics dependency reaches the store SPI). A scoped view meters
 * too, so a tenant/repository operation is timed the same as a root one. The decorator adds nothing to the bytes:
 * a {@link ArtifactStore.RangedSink} target and the content-addressed {@link #writeBlob} pass straight through to
 * the leaf backend.
 */
public final class MeteringArtifactStore implements ArtifactStore {

    private final ArtifactStore delegate;
    private final MeterRegistry registry;
    private final String backend;
    // The (op, outcome) tag set is stable and one timer is recorded on every store operation - the hottest path in
    // the product - so each timer is resolved once and reused rather than rebuilding the tag array and re-doing the
    // registry lookup per call (backend is fixed for the whole decorator tree). The map is shared with every scoped
    // view, since routing mints a fresh decorator per request through scope(); a per-instance map would be discarded
    // each request and never pay off. Bounded: op has a fixed handful of values and outcome is ok/error.
    private final ConcurrentMap<String, Timer> timers;

    /** Over {@code delegate}; {@code registry} may be null, in which case the operations are counted but not timed. */
    public MeteringArtifactStore(ArtifactStore delegate, MeterRegistry registry, String backend) {
        this(delegate, registry, backend == null || backend.isBlank() ? "filesystem" : backend, new ConcurrentHashMap<>());
    }

    private MeteringArtifactStore(ArtifactStore delegate, MeterRegistry registry, String backend,
                                  ConcurrentMap<String, Timer> timers) {
        this.delegate = delegate;
        this.registry = registry;
        this.backend = backend;
        this.timers = timers;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new MeteringArtifactStore(delegate.scope(tenant), registry, backend, timers);
    }

    @Override
    public Object identity() {
        return delegate.identity();
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        return delegate.presign(key, ttl);
    }

    @Override
    public boolean exists(String key) {
        return timedRuntime("exists", key, () -> delegate.exists(key));
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        timed("read", key, () -> {
            delegate.read(key, out);
            return null;
        });
    }

    @Override
    public InputStream open(String key) throws IOException {
        return timed("open", key, () -> delegate.open(key));
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        timed("write", key, () -> {
            delegate.write(key, in);
            return null;
        });
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        return timed("writeBlob", null, () -> delegate.writeBlob(in));
    }

    @Override
    public long size(String key) throws IOException {
        return timed("size", key, () -> delegate.size(key));
    }

    @Override
    public Optional<Listed> listed(String key) throws IOException {
        // Forwarded, not inherited: the SPI default answers presence from exists() and drops the time the backend
        // would have carried, which is what a caller of listed() came for.
        return timed("listed", key, () -> delegate.listed(key));
    }

    @Override
    public void delete(String key) throws IOException {
        timed("delete", key, () -> {
            delegate.delete(key);
            return null;
        });
    }

    @Override
    public List<String> list(String prefix) {
        return timedRuntime("list", prefix, () -> delegate.list(prefix));
    }

    /**
     * Delegate the scan, for the same reason {@link #page} is delegated and with the same consequence for getting it
     * wrong.
     *
     * <p>The SPI's inherited {@code scan} is {@code scanByListing}, which walks {@code list} recursively into heap
     * and then refuses past ten thousand keys - a deliberate bound, because a fallback that buffered a namespace to
     * answer one page would be worse than one that says it cannot. A decorator that forgets this method does not
     * merely lose performance: it *replaces* the backend's native, genuinely bounded prefix listing with that
     * fallback, so a bounded question asked through the decorator becomes an unbounded one.
     *
     * <p>Measured rather than reasoned: this was missing from every decorator at once, and the image
     * stopped booting over a store holding more than ten thousand keys. A tenant existence probe - already written
     * as a point read with a page limit of one - reached this fallback through the decorator and materialised
     * 10,001 keys to answer it.
     *
     * <p>And it is metered, as {@code scan}: a scan is the listing request it is on every object store, and a bench
     * of the build cache once read a warm build as one read per hit while every hit's stamp scan went uncounted.
     */
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return timed("scan", prefix, () -> delegate.scan(prefix, startAfter, limit, consumer));
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        // Delegate to the backend's native bounded paging rather than inherit the SPI default, which materialises and
        // sorts the whole child set of the prefix into heap (list() then Collections.sort) on every page. This store is
        // the always-injected outer decorator, and the GC / store-walk / quota-recompute jobs page through it over the
        // flat, content-addressed blobs/ namespace (millions of entries on a busy repo), so the default would OOM those
        // maintenance passes - the exact whole-namespace materialisation the paging primitive and every sibling
        // decorator (Quota, ReadOnly, the object-store backends) exist to prevent.
        timedRuntime("page", prefix, () -> {
            delegate.page(prefix, startAfter, limit, consumer);
            return null;
        });
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return timed("readVersioned", key, () -> delegate.readVersioned(key));
    }

    /** Delegated rather than inherited: the default asks the delegate for the whole object to keep its token, so a
     *  metered deployment - which is any deployment wiring this decorator - would read a document it declined
     *  to hold. */
    @Override
    public Optional<Object> version(String key) throws IOException {
        return timed("version", key, () -> delegate.version(key));
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return timed("writeVersioned", key, () -> delegate.writeVersioned(key, content, expected));
    }

    /** Forwarded like {@link #page}, and for the same reason plus one: the SPI's default derives the page from names
     *  alone and drops the listing's sizes and ages, which the store contract's decorator leg measured. */
    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        timedRuntime("pageListed", prefix, () -> {
            delegate.pageListed(prefix, startAfter, limit, consumer);
            return null;
        });
    }

    @Override
    public Optional<Capacity> capacity() throws IOException {
        return timed("capacity", null, delegate::capacity);
    }

    @Override
    public void touch(String key) throws IOException {
        timed("touch", key, () -> {
            delegate.touch(key);
            return null;
        });
    }

    /** Timed and delegated rather than inherited: the inherited body buffers, so a metered deployment would lose
     *  the streaming write it is sitting in front of - and the meter would time the wrong call. */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        return timed("writeVersioned", key, () -> delegate.writeVersioned(key, content, length, expected));
    }

    private <T> T timed(String op, String key, IoCall<T> call) throws IOException {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return call.run();
        } catch (IOException | RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            record(op, key, outcome, start);
        }
    }

    private <T> T timedRuntime(String op, String key, Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            record(op, key, outcome, start);
        }
    }

    private void record(String op, String key, String outcome, long startNanos) {
        if (registry != null) {   // the timers need a registry; the counts below are the node's own and never do
            timers.computeIfAbsent(op + '\0' + outcome, _ ->
                            registry.timer("jenreg.store.operations", "op", op, "backend", backend, "outcome", outcome))
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
        COUNTS.computeIfAbsent(op, _ -> new LongAdder()).increment();
        if (families) {
            FAMILIES.computeIfAbsent(op + ' ' + family(key), _ -> new LongAdder()).increment();
        }
    }

    /**
     * The key's family: its first two path segments, with an identity folded out of each.
     *
     * <p>That is the grain the store is laid out in - {@code blobs/<hash>}, {@code publish/<format>/...},
     * {@code gc/<pass>/refs/...} - and therefore the grain a cost is argued in. A count by operation alone says a
     * collection issues three hundred thousand versioned reads without saying whether they are the collector's or
     * the walk's, which is the difference between a change that helps and one that does nothing.
     */
    static String family(String key) {
        if (key == null || key.isBlank()) {
            return "-";
        }
        int first = key.indexOf('/');
        if (first < 0) {
            return key;
        }
        int second = key.indexOf('/', first + 1);
        // Three segments rather than two: two collapse a walk's manifest and its segment state into one family,
        // and those are different costs with different fixes. The third is folded below when it is an identity,
        // so the map stays the handful of spaces the layout has.
        int third = second < 0 ? -1 : key.indexOf('/', second + 1);
        String family = third < 0 ? (second < 0 ? key : key) : key.substring(0, third);
        // A hash, a pass number or a uuid is an identity rather than a family: fold them, or the map would grow
        // with the store instead of staying the handful of spaces the layout has.
        return family.replaceAll("[0-9a-f]{32,}", "<id>").replaceAll("/[0-9]+", "/<n>");
    }

    /** Every operation this node has issued to its store, by name, since it started - the count the observability
     *  report carries as {@code jenreg.store.ops.<op>}, so a suite that drives the product as booted can hold a
     *  download, a publish or a walked object to a standard of reads and writes, and a soak can show the operations
     *  per request staying flat as the store fills. The Micrometer timer above is per backend and outcome for a
     *  dashboard; this is the plain count a harness reads over HTTP. */
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    /** The same operations by key family, kept only when {@link #families(boolean)} switched it on - one map
     *  lookup and a string concatenation per store call is not something the read path should pay to answer a
     *  question nobody asked. */
    private static final Map<String, LongAdder> FAMILIES = new ConcurrentHashMap<>();

    private static volatile boolean families;

    /** Count by key family as well as by operation, for a run that is measuring where its store cost goes. */
    public static void families(boolean enabled) {
        families = enabled;
    }

    /** The operations this node issued so far, by operation and key family; empty unless switched on. */
    public static Map<String, Long> byFamily() {
        Map<String, Long> counts = new TreeMap<>();
        FAMILIES.forEach((name, count) -> counts.put(name, count.sum()));
        return counts;
    }

    /** The operations this node issued so far, by name. */
    public static Map<String, Long> operations() {
        Map<String, Long> counts = new TreeMap<>();
        COUNTS.forEach((op, count) -> counts.put(op, count.sum()));
        return counts;
    }

    /** Whether {@code op} writes: the operation classes a bill separates, twelve to one on every object store. */
    public static boolean writes(String op) {
        return switch (op) {
            case "write", "writeBlob", "writeVersioned", "delete", "touch" -> true;
            default -> false;
        };
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T run() throws IOException;
    }
}
