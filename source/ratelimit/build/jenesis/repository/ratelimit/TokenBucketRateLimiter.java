package build.jenesis.repository.ratelimit;

import module java.base;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.server.spi.RateLimiter;

/**
 * An in-memory token-bucket rate limiter, keyed by an arbitrary string (a tenant, or a credential hash). Each key
 * gets a bucket that refills at the requested rate and holds up to one window's worth of burst; a request consumes
 * one token, and {@link #allow} is false when the bucket is empty. A rate of zero or less is unlimited. The rate is
 * passed per call rather than fixed at construction, so a configuration change takes effect on the next request
 * without rebuilding anything; the bucket refills and caps at the new rate.
 *
 * <p>The limiter is per process, so in a replicated deployment the effective ceiling is the configured rate times the
 * node count - the trade for keeping a coordination service off the hot path.
 *
 * <p>The buckets are bounded: one unused for a minute is forgotten, having refilled, and past {@link #MAX_BUCKETS} the
 * least recent goes, so a flood of distinct keys - fabricated credentials, addresses - cannot exhaust memory. It
 * reports {@code jenrepo.ratelimit.buckets}, the keys it holds, as a gauge, and a {@code jenrepo.ratelimit.limiter}
 * health check. Buckets refill lazily
 * on the request path, so there is no task status.
 */
public final class TokenBucketRateLimiter implements RateLimiter, ObservabilitySource {

    /** The most buckets held at once. A flood of distinct keys past it evicts the least recent, and an evicted key
     *  starts again with a full bucket - which only ever admits more, never refuses a request it should not. */
    static final int MAX_BUCKETS = 200_000;

    /** How long an unused bucket is kept: at any ceiling of one a minute or more it has refilled by then, so a fresh
     *  bucket answers exactly as it would. */
    private static final Duration IDLE = Duration.ofMinutes(1);

    private final Cache<String, Bucket> buckets;
    private final LongSupplier clock;

    public TokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    private TokenBucketRateLimiter(LongSupplier clock) {
        this.clock = clock;
        this.buckets = Caffeine.newBuilder().maximumSize(MAX_BUCKETS).expireAfterAccess(IDLE)
                .ticker(clock::getAsLong).build();
    }

    /**
     * A limiter reading time from a supplied nanosecond clock instead of {@link System#nanoTime}: a test seam with no
     * production caller, since a limiter's behaviour is a function of elapsed time and a suite that cannot move the
     * clock can only assert it by sleeping.
     */
    public TokenBucketRateLimiter withClock(LongSupplier clock) {
        return new TokenBucketRateLimiter(clock);
    }

    @Override
    public boolean allow(String key, double permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            return true;
        }
        return buckets.get(key, ignored -> new Bucket()).tryAcquire(permitsPerMinute, clock.getAsLong());
    }

    @Override
    public List<Metric> metrics() {
        return List.of(Metric.gauge("jenrepo.ratelimit.buckets",
                "Rate-limit buckets currently held, one per tenant, credential and client address that made a "
                        + "request within the last minute, bounded at " + MAX_BUCKETS + ".",
                buckets.estimatedSize(), ""));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return List.of(HealthCheck.up("jenrepo.ratelimit.limiter",
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
