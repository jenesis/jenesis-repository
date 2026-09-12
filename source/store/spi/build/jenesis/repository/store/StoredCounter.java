package build.jenesis.repository.store;

import module java.base;

/**
 * A running total under one store key, moved by signed deltas under compare-and-set and recomputed from truth by a
 * pass: the bytes a tenant has stored against its quota, the bytes published beneath a browse folder. The counter is a
 * plain decimal string, floored at zero, and a value that does not parse reads as zero - a truncated write or a hand
 * edit never throws through the fold that maintains it.
 *
 * <p>{@link #add} is best-effort by design: a delta that loses every retry is dropped and {@code false} says so, and the
 * caller logs the drop naming the pass that recomputes the total - the quota's {@code recompute}, the browse's
 * {@code rollUpSizes} - so a counter drifting under sustained contention is visible before the pass corrects it, not a
 * silent surprise. It fails toward the last landed total, never toward a wrong one, which is what makes the drift
 * acceptable where an identity rollup's is not: a stale byte count is a stale number on a screen, a stale identity is
 * a wrong {@code 304}. That difference in failure model is why this is not the rollup's class and never will be.
 *
 * <p>The quota decorator and the subtree-size observer each wrote this - the same parse, the same floor, the same
 * {@link Retries#tryUpdate}, the same warning shape - and the observer's javadoc said "exactly as the quota does" three
 * times over. One class, one test.
 *
 * <p>A pass cadence is this counter too: a task that reconciles every {@code n}th pass reads {@code read() + 1 >= n},
 * {@code add(1)}s on an incremental pass and {@code set(0)}s after the full one. That used to be a second class,
 * {@code PassCounter}, with the same key, the same decimal body and a plain read-then-write where this one
 * compare-and-sets; a lost race there cost at most one pass of cadence, which the compare-and-set costs never.
 *
 * <p><b>A delta may be deferred.</b> {@link #addLater} keeps the delta in this process and a flusher folds every
 * pending delta of a key into one compare-and-set per {@code jenreg.counters.flush} (a minute by default) and on
 * shutdown; {@link #read} answers the stored value plus what this node still holds, so the node that wrote sees
 * its own deltas at once and the check a write makes against its limit is exact here. The folder-size roll-ups
 * paid five or six compare-and-sets per publish through this counter, each a round trip and a write-class call on
 * an object store, for a total the recompute recomputes anyway; a lost buffer is the drift the counter's own
 * documentation already accepts and the reconcile already heals.
 */
public final class StoredCounter {

    private final ArtifactStore store;

    private final String key;

    public StoredCounter(ArtifactStore store, String key) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The store key the counter lives under, so a storage manifest can name it. */
    public String key() {
        return key;
    }

    /** The current total: zero when never counted, or when the stored value does not parse. */
    public long read() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        return Math.max(0L, (stored.isEmpty() ? 0L : parse(stored.get().content())) + pending());
    }

    /** {@link #read}, empty when nothing is stored and nothing is pending - for a reader that shows "unknown" rather
     *  than zero for a counter that was never written. */
    public OptionalLong readIfPresent() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        Deferred deferred = DEFERRED.get(deferredKey());
        if (stored.isEmpty() && deferred == null) {
            return OptionalLong.empty();   // never written, and never counted on this node either
        }
        long pending = deferred == null ? 0L : deferred.pending.get();
        return OptionalLong.of(Math.max(0L, (stored.isEmpty() ? 0L : parse(stored.get().content())) + pending));
    }

    /** Remove the counter - the stored object and whatever this node still held pending for it - for a folder or a
     *  subject that no longer exists; a delta deferred against it would otherwise re-create it at the next flush. */
    public void delete() throws IOException {
        DEFERRED.remove(deferredKey());
        store.delete(key);
    }

    /** Move the total by {@code delta}, floored at zero, retrying a lost compare-and-set through {@link Retries};
     *  {@code false} when every try lost and the delta was dropped for the recomputing pass to heal. */
    /** The flush cadence setting: an ISO-8601 or suffixed duration; {@code 0} flushes every deferred delta at once,
     *  which is {@link #add}. */
    public static final String FLUSH_SETTING = "counters.flush";

    /** The default cadence as an operator writes it - a compile-time constant so the settings reference prints it
     *  rather than {@code (computed)}; {@link #DEFAULT_FLUSH} parses this, so there is one spelling. */
    public static final String DEFAULT_FLUSH_TEXT = "PT1M";

    public static final Duration DEFAULT_FLUSH = Duration.parse(DEFAULT_FLUSH_TEXT);

    private static final Map<String, Deferred> DEFERRED = new ConcurrentHashMap<>();
    private static final AtomicReference<ScheduledExecutorService> FLUSHER = new AtomicReference<>();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(StoredCounter.class);

    /** A key's deltas this process has not yet written, with the counter that will write them. */
    private static final class Deferred {
        private final StoredCounter counter;
        private final AtomicLong pending = new AtomicLong();

        private Deferred(StoredCounter counter) {
            this.counter = counter;
        }
    }

    /**
     * Add {@code delta} later: it is folded into the stored value by the next flush, and {@link #read} on this node
     * already counts it. With {@link #FLUSH_SETTING} at {@code 0} this is {@link #add}.
     */
    public void addLater(long delta) throws IOException {
        Duration cadence = flushCadence();
        if (cadence.isZero()) {
            add(delta);
            return;
        }
        DEFERRED.computeIfAbsent(deferredKey(), _ -> new Deferred(this)).pending.addAndGet(delta);
        startFlusher(cadence);
    }

    /** Fold every deferred delta into its counter now - the shutdown hook, and what a test calls. Answers how many
     *  counters were written. */
    public static int flushNow() {
        int written = 0;
        for (Map.Entry<String, Deferred> entry : DEFERRED.entrySet()) {
            Deferred deferred = entry.getValue();
            long sum = deferred.pending.getAndSet(0L);
            if (sum == 0L) {
                continue;
            }
            try {
                if (deferred.counter.add(sum)) {
                    written++;
                } else {
                    deferred.pending.addAndGet(sum);   // every try lost: keep the delta for the next flush
                    LOGGER.warn("deferred counter update of {} on {} lost every compare-and-set; kept for the next flush",
                            sum, deferred.counter.key);
                }
            } catch (IOException | RuntimeException failure) {
                deferred.pending.addAndGet(sum);
                LOGGER.warn("deferred counter update of {} on {} could not be written; kept for the next flush: {}",
                        sum, deferred.counter.key, failure.toString());
            }
        }
        return written;
    }

    /**
     * Fold every deferred delta into its counter now and forget them all - what a node does as it closes its store,
     * through the {@link Settling} bean its composition root declares.
     *
     * <p>The flusher is one per process and keeps a closed node's deltas beside a live node's, keyed by store, so
     * without this a delta pending when a node stopped was written at the next tick into a store that was gone. In
     * a test JVM that boots servers over temporary directories that is a directory recreated under one JUnit has
     * just deleted, reported as {@code Failed to close extension context} with a {@code DirectoryNotEmptyException}
     * naming a repository nobody wrote to after the suite ended - the shape measured 2026-09-12 on
     * {@code RoutedServingE2ETest}, a suite that never mentions a counter. Forgetting every entry rather than the
     * closing store's alone is deliberate: an early flush of a live node's delta is always correct, and matching a
     * scoped view's identity to its root's is a per-backend question this class should not have to answer.
     */
    public static int settle() {
        int written = flushNow();
        DEFERRED.clear();
        return written;
    }

    /** The deferred counters as a closeable for a node's shutdown: closing it {@linkplain #settle() settles} them,
     *  the way {@code StoredListing.Deferred} finishes the derived twins a stopping node queued. */
    public static final class Settling implements AutoCloseable {

        public Settling() {
        }

        @Override
        public void close() {
            settle();
        }
    }

    /** What this node still holds for this key, unwritten. */
    private long pending() {
        Deferred deferred = DEFERRED.get(deferredKey());
        return deferred == null ? 0L : deferred.pending.get();
    }

    private String deferredKey() {
        return store.identity() + "\u0000" + key;
    }

    static Duration flushCadence() {
        String setting = Features.settings().apply(FLUSH_SETTING);
        if (setting == null || setting.isBlank()) {
            return DEFAULT_FLUSH;
        }
        return StoreCache.ttl(setting);   // the same duration grammar, 0 meaning "not deferred"
    }

    private static void startFlusher(Duration cadence) {
        if (FLUSHER.get() != null) {
            return;
        }
        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jenesis-counter-flush");
            thread.setDaemon(true);
            return thread;
        });
        if (FLUSHER.compareAndSet(null, flusher)) {
            long millis = Math.max(1L, cadence.toMillis());
            flusher.scheduleAtFixedRate(StoredCounter::flushNow, millis, millis, TimeUnit.MILLISECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(StoredCounter::flushNow, "jenesis-counter-flush-exit"));
        } else {
            flusher.shutdownNow();
        }
    }

    public boolean add(long delta) throws IOException {
        return Retries.tryUpdate(store, key, stored -> {
            long current = stored.isEmpty() ? 0L : parse(stored.get().content());
            return Long.toString(Math.max(0L, current + delta)).getBytes(StandardCharsets.UTF_8);
        });
    }

    /** Store a total recomputed from truth, whatever the counter held - the pass's authoritative correction. A lost
     *  race is left to the next pass, as the caller's own last-writer-wins write always was. */
    public void set(long total) throws IOException {
        Deferred deferred = DEFERRED.get(deferredKey());
        if (deferred != null) {
            deferred.pending.set(0L);   // the recompute is the truth; a delta it did not see is superseded by it
        }
        byte[] body = Long.toString(Math.max(0L, total)).getBytes(StandardCharsets.UTF_8);
        Retries.tryUpdate(store, key, _ -> body);
    }

    private static long parse(byte[] content) {
        try {
            return Long.parseLong(new String(content, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException _) {
            return 0L;
        }
    }
}
