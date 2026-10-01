package build.jenesis.repository.feed;

import module java.base;

/**
 * Every bound and knob one feed runs under, as one immutable value: the fail mode, the timeouts, the pagination and
 * byte caps, the retry schedule and the refresh intervals, stated once where a feed builds its client.
 *
 * <p>Start from {@link #closed()} or {@link #soft()} and narrow with the withers, each named like its accessor:
 * {@snippet :
 * FeedPolicy.closed().maxPages(10).requestTimeout(Duration.ofSeconds(15))
 * }
 *
 * <p>The {@link #deadline()} bounds the whole fetch, every page and retry together, since a per-request timeout does
 * not bound a paginated fetch: fifty pages at thirty seconds each would park a single-flight refresh for twenty-five
 * minutes.
 */
public record FeedPolicy(FailMode failMode,
                         Duration requestTimeout,
                         Duration deadline,
                         int maxPages,
                         int maxAttempts,
                         Duration backoff,
                         double backoffMultiplier,
                         Duration maxBackoff,
                         long maxResponseBytes,
                         long maxSnapshotBytes,
                         Duration refreshInterval,
                         Duration retryInterval,
                         boolean sameOriginOnly) {

    /** What a feed's consumer sees when the feed cannot answer. The mode is a property of the signal: a missing
     *  advisory answer would read as "clean" and fails closed, while a missing exploit-probability or maintainer-health
     *  score only leaves a ranking aid absent and may fail soft. */
    public enum FailMode {
        /** A failure is raised as a {@link FeedException}; the mode of every advisory and malicious-package feed. */
        CLOSED,
        /** A failure degrades: the fetch answers {@link FeedClient.Status#DEGRADED}, the caller keeps what it had, and
         *  one warning is logged. The mode of a ranking aid, where blocking every publish on a vendor outage would be
         *  wrong. */
        SOFT
    }

    public FeedPolicy {
        Objects.requireNonNull(failMode, "failMode");
        positive(requestTimeout, "requestTimeout");
        positive(deadline, "deadline");
        positive(backoff, "backoff");
        positive(maxBackoff, "maxBackoff");
        positive(refreshInterval, "refreshInterval");
        positive(retryInterval, "retryInterval");
        if (maxPages < 1) {
            throw new IllegalArgumentException("A feed draws at least one page: " + maxPages);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("A feed makes at least one attempt: " + maxAttempts);
        }
        if (backoffMultiplier < 1) {
            throw new IllegalArgumentException("A backoff multiplier never shrinks the delay: " + backoffMultiplier);
        }
        if (maxResponseBytes < 1 || maxSnapshotBytes < 1) {
            throw new IllegalArgumentException(
                    "Byte caps are positive: " + maxResponseBytes + " / " + maxSnapshotBytes);
        }
        if (deadline.compareTo(requestTimeout) < 0) {
            throw new IllegalArgumentException("A fetch deadline (" + deadline
                    + ") shorter than one request timeout (" + requestTimeout + ") could never draw a page");
        }
    }

    /** The fail-closed default: 30 s per request, a 5 min whole-fetch deadline, 50 pages, 3 attempts with a 1 s
     *  exponential backoff capped at 30 s, a 64 MiB response cap and a 16 MiB snapshot cap, refreshed every 6 hours and
     *  retried after 15 minutes, refusing a cross-origin cursor. */
    public static FeedPolicy closed() {
        return new FeedPolicy(FailMode.CLOSED,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                50,
                3,
                Duration.ofSeconds(1),
                2.0,
                Duration.ofSeconds(30),
                64L * 1024 * 1024,
                16L * 1024 * 1024,
                Duration.ofHours(6),
                Duration.ofMinutes(15),
                true);
    }

    /** {@link #closed()}'s bounds with {@link FailMode#SOFT}, for a ranking aid. */
    public static FeedPolicy soft() {
        return closed().failMode(FailMode.SOFT);
    }

    public FeedPolicy failMode(FailMode value) {
        return new FeedPolicy(value, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy requestTimeout(Duration value) {
        return new FeedPolicy(failMode, value, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy deadline(Duration value) {
        return new FeedPolicy(failMode, requestTimeout, value, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy maxPages(int value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, value, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy maxAttempts(int value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, value, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    /** The three retry-curve withers are test seams with no production caller: no source has needed a curve other than
     *  the default, and a suite that cannot shorten it must wait out real backoff. They are not operator dials; a
     *  source that needs a gentler curve for a rate-limited vendor sets one here, as it sets its page count. */
    public FeedPolicy backoff(Duration value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, value, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy backoffMultiplier(double value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, value,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy maxBackoff(Duration value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                value, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy maxResponseBytes(long value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, value, maxSnapshotBytes, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy maxSnapshotBytes(long value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, value, refreshInterval, retryInterval, sameOriginOnly);
    }

    public FeedPolicy refreshInterval(Duration value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, value, retryInterval, sameOriginOnly);
    }

    public FeedPolicy retryInterval(Duration value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, value, sameOriginOnly);
    }

    /** Whether a pagination cursor must stay on the first request's origin. Leave it on: a cursor pointing elsewhere
     *  would carry the request's credential with it. Turn it off only for a mirror that legitimately paginates across
     *  hosts, and say why where the policy is built. */
    public FeedPolicy sameOriginOnly(boolean value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, value);
    }

    /** The delay before {@code attempt} (1-based, so attempt 2 is the first retry): the vendor's {@code Retry-After}
     *  when it named one, else exponential backoff from {@link #backoff()}, both capped by {@link #maxBackoff()}. No
     *  jitter, so a schedule is exact; spreading a fleet's refreshes belongs where they are scheduled. */
    public Duration delayBefore(int attempt, Optional<Duration> retryAfter) {
        if (retryAfter.isPresent() && !retryAfter.get().isNegative()) {
            return min(retryAfter.get(), maxBackoff);
        }
        double scaled = backoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, attempt - 2));
        long millis = scaled >= maxBackoff.toMillis() ? maxBackoff.toMillis() : (long) scaled;
        return Duration.ofMillis(Math.max(0, millis));
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static void positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("A " + name + " is a positive duration: " + duration);
        }
    }
}
