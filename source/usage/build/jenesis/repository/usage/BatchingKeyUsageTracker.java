package build.jenesis.repository.usage;

import module java.base;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.BatchingWorker;
import build.jenesis.repository.server.spi.KeyUsageTracker;

/**
 * Credential usage tracking ({@code jenrepo.track-key-usage}, on unless switched off), off the request path on its own
 * worker thread, started and stopped through Spring's bean lifecycle so {@link #close} stops and joins it, and
 * watched by a health indicator through {@link #alive} and {@link #dropped}. An allowed request offers a {@link Hit}
 * (tenant, key hash, source address) to a bounded queue without blocking, dropped when saturated, since usage is an
 * informational signal rather than an audit log. The thread drains the queue into a per-credential accumulator that
 * counts every hit and remembers the last address, and flushes each credential through
 * {@link Authorization#recordUsed} at most once per UTC day, adding the delta since the last flush: the persisted count
 * lags but converges, no hit within a process lifetime is lost, and a crash forfeits only the unflushed tail.
 * {@link #drain} is public so a test can drive it without the thread; {@link #record} is a no-op when tracking is off.
 *
 * <p>An enabled tracker reports its queue depth against the capacity ({@code jenrepo.usage.queue}), the accumulators
 * it holds ({@code jenrepo.usage.tracked}), the hits dropped ({@code jenrepo.usage.dropped}), a
 * {@code jenrepo.usage.worker} health check (DOWN when the worker died with tracking on) and a
 * {@code jenrepo.usage.flush} task status stamped with the last drain. A disabled tracker reports nothing, as a
 * disabled plugin is not listed.
 */
public final class BatchingKeyUsageTracker extends BatchingWorker<BatchingKeyUsageTracker.Hit>
        implements KeyUsageTracker, ObservabilitySource {

    public record Hit(String tenant, String hash, String address) {
    }

    private static final class Pending {
        private long count;
        private long flushed;
        private String address;
        private Instant when;
    }

    private static final int QUEUE_CAPACITY = 100_000;

    private final Authorization authorization;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> writtenDay = new ConcurrentHashMap<>();
    private volatile Instant lastDrain;

    public BatchingKeyUsageTracker(Authorization authorization, boolean enabled) {
        super("jenesis-repository-key-usage", enabled, QUEUE_CAPACITY);
        this.authorization = authorization;
    }

    @Override
    public void record(String tenant, String hash, String address) {
        if (tenant != null && hash != null) {
            offer(new Hit(tenant, hash, address));
        }
    }

    @Override
    public String what() {
        return "credential use";
    }

    @Override
    public String cadence() {
        return "once a UTC day per credential";
    }

    /** The credentials holding uses not yet written, and the uses still queued. */
    @Override
    public long pending() {
        long credentials = pending.values().stream().filter(value -> {
            synchronized (value) {
                return value.count > value.flushed;
            }
        }).count();
        return credentials + queueDepth();
    }

    /** Asked to write now: fold what is queued and write every credential's delta, one already written today
     *  included. */
    @Override
    protected void onWriteRequested(Instant now) {
        for (Hit hit : drainQueue()) {
            accumulate(hit, now);
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            flush(entry.getKey(), entry.getValue(), today);
        }
    }

    /**
     * The worker has terminated (or was never started), so every accumulator is quiescent. Drains what the stopped
     * worker left queued, so a clean shutdown forfeits no accepted hit, then flushes every residual delta, including
     * one for a credential already flushed today.
     *
     * <p>A worker that did not stop within the grace window is still draining, and flushing now could race its count
     * and mark a hit flushed without persisting it; its next drain flushes the tail instead.
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
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            flush(entry.getKey(), entry.getValue(), today);
        }
    }

    @Override
    public void drain(Collection<Hit> batch, Instant now) {
        for (Hit hit : batch) {
            accumulate(hit, now);
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (!today.equals(writtenDay.get(entry.getKey()))) {
                flush(entry.getKey(), entry.getValue(), today);
            }
        }
        // Drop a fully flushed credential not written today, so the maps are bounded by the credentials seen in one
        // day; a later hit rebuilds a fresh accumulator and flushes its new delta.
        pending.entrySet().removeIf(entry -> {
            Pending value = entry.getValue();
            synchronized (value) {
                if (value.count == value.flushed && !today.equals(writtenDay.get(entry.getKey()))) {
                    writtenDay.remove(entry.getKey());
                    return true;
                }
                return false;
            }
        });
        lastDrain = now;
    }

    private void accumulate(Hit hit, Instant now) {
        Pending entry = pending.computeIfAbsent(hit.tenant() + "/" + hit.hash(), key -> new Pending());
        synchronized (entry) {
            entry.count++;
            entry.when = now;
            if (hit.address() != null) {
                entry.address = hit.address();
            }
        }
    }

    public int tracked() {
        return pending.size();
    }

    @Override
    public List<Metric> metrics() {
        if (!enabled()) {
            return List.of();
        }
        return List.of(
                Metric.bounded("jenrepo.usage.queue",
                        "Credential-use hits buffered off the request path waiting for the worker to drain them, "
                                + "against the fixed queue bound past which a hit is dropped rather than blocking a request.",
                        queueDepth(), capacity(), "hits"),
                Metric.gauge("jenrepo.usage.tracked",
                        "Per-credential accumulators currently held - bounded by the credentials seen in the current "
                                + "UTC day (plus any carrying an unflushed delta), not every credential ever seen.",
                        tracked(), ""),
                Metric.counter("jenrepo.usage.dropped",
                        "Credential-use hits dropped because the in-memory queue was saturated - back-pressure, not "
                                + "an outage; usage is an informational signal, never an audit log.",
                        dropped(), "hits"));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        if (!enabled()) {
            return List.of();
        }
        String description = "Credential-usage worker thread is started and draining hits off the request path.";
        return List.of(alive()
                ? HealthCheck.up("jenrepo.usage.worker", description)
                : HealthCheck.of("jenrepo.usage.worker", description, Health.DOWN,
                        "usage tracking is switched on but its worker thread is not running"));
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        if (!enabled()) {
            return List.of();
        }
        boolean alive = alive();
        return List.of(TaskStatus.ran("jenrepo.usage.flush",
                "Background worker draining buffered credential-use hits and flushing each credential's running "
                        + "count and last address through the authorization store at most once per UTC day.",
                alive ? TaskStatus.State.RUNNING : TaskStatus.State.FAILED, lastDrain, null,
                alive ? "draining the usage queue and flushing each credential at most once per UTC day"
                        : "worker thread is not running"));
    }

    private void flush(String key, Pending entry, LocalDate day) {
        // Read the fields together under the monitor, and advance `flushed` only to this snapshot, so a hit landing
        // while recordUsed is in flight stays an unflushed delta rather than being marked flushed unpersisted.
        long snapshot;
        long delta;
        Instant when;
        String address;
        synchronized (entry) {
            snapshot = entry.count;
            delta = snapshot - entry.flushed;
            when = entry.when;
            address = entry.address;
        }
        if (delta <= 0) {
            return;
        }
        int slash = key.indexOf('/');
        try {
            if (authorization.recordUsed(key.substring(0, slash), key.substring(slash + 1), when, address, delta)) {
                synchronized (entry) {
                    entry.flushed = snapshot;
                }
                writtenDay.put(key, day);
            }
            // False: every compare-and-set lost, so the delta stays unflushed for the next flush.
        } catch (IOException e) {
            // best-effort
        }
    }
}
