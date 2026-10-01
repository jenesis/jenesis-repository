package build.jenesis.repository.store.metering;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoreBindings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * An {@link ArtifactStore} decorator timing each store operation as {@code jenrepo.store.operations}, tagged by
 * operation ({@code op}), backend ({@code backend}) and {@code outcome} ({@code ok}/{@code error}), so a deployment
 * sees per-backend store latency. Wired only in the distribution, with a {@link MeterRegistry}, so no metrics
 * dependency reaches the store SPI; a scoped view meters too. The bytes pass straight through: a
 * {@link ArtifactStore.RangedSink} target and {@link #writeBlob} reach the leaf backend unchanged.
 */
public final class MeteringArtifactStore implements ArtifactStore {

    private final ArtifactStore delegate;
    private final MeterRegistry registry;
    private final String backend;
    // One timer per (op, outcome), resolved once and reused on the hottest path rather than looked up per call; the map
    // is shared with every scoped view, since routing mints a decorator per request through scope(). Bounded: a handful
    // of ops, two outcomes, one backend.
    private final ConcurrentMap<String, Timer> timers;

    /** Whether this store also counts by key family - {@code jenrepo.store-families}, for a run measuring where its
     *  store cost goes. */
    private final boolean families;

    /** Over {@code delegate}; with a null {@code registry} operations are counted but not timed. */
    public MeteringArtifactStore(ArtifactStore delegate, MeterRegistry registry, String backend) {
        this(delegate, registry, backend, false);
    }

    /** As {@link #MeteringArtifactStore(ArtifactStore, MeterRegistry, String)}, counting by key family too when
     *  {@code families} is set. */
    public MeteringArtifactStore(ArtifactStore delegate, MeterRegistry registry, String backend, boolean families) {
        this(delegate, registry, backend == null || backend.isBlank() ? "filesystem" : backend, new ConcurrentHashMap<>(),
                families);
    }

    private MeteringArtifactStore(ArtifactStore delegate, MeterRegistry registry, String backend,
                                  ConcurrentMap<String, Timer> timers, boolean families) {
        this.delegate = delegate;
        this.registry = registry;
        this.backend = backend;
        this.timers = timers;
        this.families = families;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new MeteringArtifactStore(delegate.scope(tenant), registry, backend, timers, families);
    }

    @Override
    public Object identity() {
        return delegate.identity();
    }

    @Override
    public StoreBindings bindings() {
        return delegate.bindings();
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
    public InputStream open(String key, long offset) throws IOException {
        return timed("open", key, () -> delegate.open(key, offset));
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
        // Forwarded, not inherited: the default answers presence from exists() and drops the time the caller came for.
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
     * Delegated, as {@link #page} is. The inherited {@code scan} is {@code scanByListing}, which lists recursively into
     * heap and refuses past ten thousand keys; a decorator inheriting it would replace the backend's bounded prefix
     * listing with that fallback, so a tenant existence probe - a point read with a page limit of one - would
     * materialise 10,001 keys, and the image would not boot over a store larger than that.
     *
     * <p>Metered as {@code scan}, since a scan is a listing request on every object store.
     */
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return timed("scan", prefix, () -> delegate.scan(prefix, startAfter, limit, consumer));
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        // Delegated to the backend's bounded paging rather than the default, which lists and sorts the prefix's whole
        // child set per page. This is the always-injected outer decorator, and maintenance passes page the flat blobs/
        // namespace through it - millions of entries - which the default would exhaust the heap over.
        timedRuntime("page", prefix, () -> {
            delegate.page(prefix, startAfter, limit, consumer);
            return null;
        });
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return timed("readVersioned", key, () -> delegate.readVersioned(key));
    }

    /** Delegated: the default reads the whole object to keep its token, so every metered deployment would read a
     *  document it declined to hold. */
    @Override
    public Optional<Object> version(String key) throws IOException {
        return timed("version", key, () -> delegate.version(key));
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return timed("writeVersioned", key, () -> delegate.writeVersioned(key, content, expected));
    }

    /** Forwarded like {@link #page}, and because the default derives the page from names alone and drops the sizes and
     *  ages the store contract's decorator leg holds. */
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

    /** Timed and delegated: the inherited body buffers, so this decorator would lose the streaming write it sits in
     *  front of and time the wrong call. */
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
                            registry.timer("jenrepo.store.operations", "op", op, "backend", backend, "outcome", outcome))
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
        COUNTS.computeIfAbsent(op, _ -> new LongAdder()).increment();
        if (families) {
            FAMILIES.computeIfAbsent(op + ' ' + family(key), _ -> new LongAdder()).increment();
        }
    }

    /** The key's family: its leading path segments with any identity folded out - {@code blobs/<hash>},
     *  {@code publish/<format>/...}, {@code gc/<pass>/refs/...} - the grain the store is laid out in and so the grain a
     *  cost is argued in. A count by operation alone cannot say whether a collection's reads are the collector's or the
     *  walk's. */
    static String family(String key) {
        if (key == null || key.isBlank()) {
            return "-";
        }
        int first = key.indexOf('/');
        if (first < 0) {
            return key;
        }
        int second = key.indexOf('/', first + 1);
        // Three segments, since two would merge a walk's manifest and its segment state, which are different costs.
        int third = second < 0 ? -1 : key.indexOf('/', second + 1);
        String family = third < 0 ? (second < 0 ? key : key) : key.substring(0, third);
        // A hash, a pass number or a uuid is an identity, folded so the map stays the layout's handful of spaces.
        return family.replaceAll("[0-9a-f]{32,}", "<id>").replaceAll("/[0-9]+", "/<n>");
    }

    /** Every operation this node has issued to its store, by name - the count the observability report carries as
     *  {@code jenrepo.store.ops.<op>}, so a suite driving the booted product can hold a download, a publish or a walked
     *  object to a standard of reads and writes, and a soak can show operations per request staying flat. The
     *  Micrometer timer is per backend and outcome for a dashboard; this is the plain count a harness reads over HTTP.
     *  Process-wide, since a process is one node. */
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    /** The same operations by key family, kept only by a store built to count them, since a map lookup and a
     *  concatenation per call is not something the read path should pay unasked. Process-wide, as {@link #COUNTS}
     *  is. */
    private static final Map<String, LongAdder> FAMILIES = new ConcurrentHashMap<>();

    /** The operations this node issued, by operation and key family; empty unless switched on. */
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

    /** Whether {@code op} writes - the two classes a bill separates, twelve to one on every object store. */
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
