package build.jenesis.repository.ratelimit;

import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.server.spi.RateLimiter;

import module java.base;

/**
 * An in-memory token-bucket rate limiter, keyed by an arbitrary string (a tenant, or a credential hash). Each key
 * gets a bucket that refills at the requested rate and holds up to one window's worth of burst; a request consumes
 * one token, and {@link #allow} is false when the bucket is empty. A rate of zero or less is unlimited. The rate is
 * passed per call rather than fixed at construction, so a configuration change takes effect on the next request
 * without rebuilding anything; the bucket simply refills and caps at the new rate.
 *
 * The limiter is per process - in a replicated deployment each node limits independently, so the effective ceiling
 * is the configured rate times the node count. That is the usual, cheap trade for not putting a coordination
 * service on the hot path; a single front door (or a small node count) keeps it close to the configured number.
 *
 * <p>It is its own {@link ObservabilitySource}: the live limiter the distribution holds reports {@code
 * jenreg.ratelimit.buckets} - the number of keys it is currently tracking, one bucket each - as a gauge, so an
 * operator watches the very memory-exhaustion vector the shared {@code anonymous} bucket is there to bound, plus a
 * {@code jenreg.ratelimit.limiter} health check that the limiter is installed and metering. There is no background
 * task (buckets refill lazily on the request path), so {@link #taskStatuses()} stays empty.
 */
public final class TokenBucketRateLimiter implements RateLimiter, ObservabilitySource {

    private static final AtomicReference<TokenBucketRateLimiter> INSTALLED = new AtomicReference<>();

    /** Register {@code instance} as the live one the discovered {@link RateLimiterObservability} reports from; the production
     *  construction site calls this once, and the last registration wins. */
    public static void install(TokenBucketRateLimiter instance) {
        INSTALLED.set(Objects.requireNonNull(instance, "instance"));
    }

    /** The installed live instance, if any - what {@link RateLimiterObservability} reports; empty before one is installed. */
    static Optional<TokenBucketRateLimiter> installed() {
        return Optional.ofNullable(INSTALLED.get());
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public TokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    private TokenBucketRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * A limiter reading time from a supplied nanosecond clock instead of {@link System#nanoTime}.
     *
     * <p><b>A test seam, and deliberately the only wither here with no production caller.</b> A rate limiter's whole
     * behaviour is a function of elapsed time, so a suite that cannot move the clock can only assert it by sleeping -
     * which makes the suite slow, and flaky on a loaded machine, for a property that is exactly specified. Every
     * other value on this type is either a constructor argument or reachable from configuration; this one is not
     * offered to an operator because there is no deployment in which reading time from somewhere other than the
     * system clock is a thing to want.
     *
     * <p>It is stated here because "public, honoured, and called by nothing in {@code source/**}" is otherwise the
     * signature of a knob that was built and never wired up, and telling the two apart by eye is what an audit of
     * this shape costs. A seam says so at its declaration.
     */
    public TokenBucketRateLimiter withClock(LongSupplier clock) {
        return new TokenBucketRateLimiter(clock);
    }

    @Override
    public boolean allow(String key, double permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            return true;
        }
        return buckets.computeIfAbsent(key, ignored -> new Bucket()).tryAcquire(permitsPerMinute, clock.getAsLong());
    }

    @Override
    public List<Metric> metrics() {
        return List.of(Metric.gauge("jenreg.ratelimit.buckets",
                "Rate-limit buckets currently tracked, one per active key - an unbounded climb is the "
                        + "memory-exhaustion vector the shared anonymous bucket is there to bound.",
                buckets.size(), ""));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return List.of(HealthCheck.up("jenreg.ratelimit.limiter",
                "In-memory token-bucket rate limiter is installed and metering requests."));
    }

    private static final class Bucket {

        private double tokens = -1.0;
        private long lastNanos;

        synchronized boolean tryAcquire(double permitsPerMinute, long now) {
            double capacity = Math.max(1.0, permitsPerMinute);
            if (tokens < 0) {
                tokens = capacity;
                lastNanos = now;
            }
            tokens = Math.min(capacity, tokens + (now - lastNanos) * (permitsPerMinute / 60_000_000_000.0));
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
