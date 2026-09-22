package build.jenesis.repository.downloads;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;
import build.jenesis.repository.server.spi.BatchingWorker;

/**
 * Opt-in download tracking ({@code jenreg.track-downloads}), off the request path on its own worker thread -
 * started and stopped through Spring's bean lifecycle (not a daemon), so {@link #close} interrupts and joins it for a
 * clean shutdown, and {@link #alive}/{@link #dropped} let a health indicator watch it. A successful read offers a
 * {@link DownloadTracker.Hit} to a bounded in-memory queue (non-blocking, counted as dropped if saturated - a
 * download count is a retention and popularity signal, not an audit log); the thread drains the queue into one
 * accumulator per coordinate version and flushes each at most once per {@code download-flush-interval}: one
 * compare-and-set on the version's document adding the hits since the last flush and carrying the newest of them.
 * The count is therefore up to one interval behind and, on an unclean stop, loses at most one interval of hits;
 * a clean stop flushes every residual. An idle worker flushes a due delta on its own clock, so a lone download is
 * not held until the next one arrives. {@link #drain} and {@link #onIdle} are public so a test can drive them
 * synchronously, without the thread.
 */
public final class BatchingDownloadTracker extends BatchingWorker<DownloadTracker.Hit> implements DownloadTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchingDownloadTracker.class);

    /** The shipped {@code download-flush-interval}: six hours, the same margin the build cache's recency accepts. */
    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofHours(6);

    private static final long BACKOFF_MILLIS = 1_000L;

    private static final int QUEUE_CAPACITY = 100_000;

    /** How often an idle worker looks for a delta whose interval has passed: seconds of lag on a due flush, and a
     *  scan of the accumulators that is not the worker's main occupation. */
    private static final Duration IDLE_SWEEP = Duration.ofSeconds(30);

    /** One coordinate version's hits since this process started: how many arrived, how many were flushed, when the
     *  newest arrived and when the last flush was. Guarded by its own monitor, as the key-usage tracker's are. */
    private static final class Pending {
        private long count;
        private long flushed;
        private Instant last;
        private Instant flushedAt;
    }

    private final DownloadTrackerProvider.Inventories inventories;
    private final Duration flushInterval;
    private final Map<Hit, Pending> pending = new ConcurrentHashMap<>();
    // Flushes that failed with an IOException: folded into dropped() so a store that always fails to persist is
    // visible on the health surface, rather than the tracker reporting alive && dropped==0 while writing nothing.
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile Instant nextIdleSweep = Instant.EPOCH;

    /** @param flushInterval how long hits are held before a flush; {@code null} or zero flushes on every drain */
    public BatchingDownloadTracker(DownloadTrackerProvider.Inventories inventories, boolean enabled,
                                   Duration flushInterval) {
        super("jenesis-repository-downloads", enabled, QUEUE_CAPACITY);
        this.inventories = inventories;
        this.flushInterval = flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()
                ? null : flushInterval;
    }

    /** The interval hits are held for, or empty when every drain flushes. */
    @Override
    public Optional<Duration> flushInterval() {
        return Optional.ofNullable(flushInterval);
    }

    @Override
    public long dropped() {
        return super.dropped() + writeFailures.get();
    }

    public long writeFailures() {
        return writeFailures.get();
    }

    /** The accumulators held: the coordinate versions hit within the current interval, plus any carrying an
     *  unflushed delta - never every version ever downloaded. */
    public int tracked() {
        return pending.size();
    }

    @Override
    public void record(Hit hit) {
        offer(hit);
    }

    /** Keep the worker alive across a failing iteration, but make the failure visible and back off, so a persistent
     *  failure neither spins nor stays silent. */
    @Override
    protected void onIterationFailure(RuntimeException failure) {
        LOGGER.warn("download tracker iteration failed", failure);
        try {
            Thread.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();   // the worker's next poll sees the interrupt and stops
        }
    }

    @Override
    public void drain(Collection<Hit> batch, Instant now) {
        for (Hit hit : batch) {
            accumulate(hit, now);
        }
        flush(now, false);
    }

    /** A due delta is flushed on the clock, not on the next hit: an idle worker sweeps the accumulators every
     *  {@link #IDLE_SWEEP}. */
    @Override
    public void onIdle(Instant now) {
        if (now.isBefore(nextIdleSweep)) {
            return;
        }
        nextIdleSweep = now.plus(IDLE_SWEEP);
        flush(now, false);
    }

    /**
     * The worker has terminated (or was never started), so every accumulator is quiescent: drain what the
     * interrupted worker left queued and flush every residual delta, interval or not, so a clean shutdown forfeits
     * no accepted hit. A worker that did not stop within the grace window is still draining, and flushing now would
     * race it; its next drain flushes the tail instead.
     */
    @Override
    protected void onClosed(boolean terminated) {
        if (!terminated) {
            return;
        }
        Instant now = Instant.now();
        for (Hit hit : drainQueue()) {
            accumulate(hit, now);
        }
        flush(now, true);
    }

    private void accumulate(Hit hit, Instant now) {
        Pending entry = pending.computeIfAbsent(hit, _ -> new Pending());
        synchronized (entry) {
            entry.count++;
            entry.last = now;
        }
    }

    /**
     * Flush every accumulator whose interval has passed - every one, when {@code everything} - as one compare-and-set
     * per coordinate version adding the delta since its last flush and carrying its newest hit; then drop the
     * accumulators that are flushed and past their interval, so the map is bounded by the coordinate versions hit
     * within one interval. A failed flush keeps its delta and is due again on the next pass.
     */
    private void flush(Instant now, boolean everything) {
        for (Map.Entry<Hit, Pending> entry : pending.entrySet()) {
            Hit hit = entry.getKey();
            Pending value = entry.getValue();
            synchronized (value) {
                long delta = value.count - value.flushed;
                if (delta <= 0 || !(everything || due(value, now))) {
                    continue;
                }
                try {
                    inventories.inventory(hit.tenant(), hit.repository())
                            .recordDownloads(hit.ecosystem(), hit.coordinate(), hit.version(), delta, value.last);
                    value.flushed = value.count;
                    value.flushedAt = now;
                } catch (IOException e) {
                    long failures = writeFailures.incrementAndGet();
                    if (failures == 1 || failures % 1000 == 0) {
                        LOGGER.warn("could not persist downloads (" + failures + " failures) for " + hit, e);
                    }
                }
            }
        }
        pending.entrySet().removeIf(entry -> {
            Pending value = entry.getValue();
            synchronized (value) {
                return value.count == value.flushed && value.flushedAt != null
                        && (flushInterval == null || !now.isBefore(value.flushedAt.plus(flushInterval)));
            }
        });
    }

    private boolean due(Pending value, Instant now) {
        return flushInterval == null || value.flushedAt == null || !now.isBefore(value.flushedAt.plus(flushInterval));
    }
}
