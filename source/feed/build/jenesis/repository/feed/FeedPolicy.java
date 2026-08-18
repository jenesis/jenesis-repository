package build.jenesis.repository.feed;

import module java.base;

/**
 * Every bound and every behavioural knob one feed runs under, in one immutable value: the fail mode, the timeouts,
 * the pagination and byte caps, the retry schedule and the refresh intervals. A feed module states its policy once
 * where it builds its client instead of scattering a {@code MAX_PAGES} constant, a request timeout and a TTL across
 * its implementation - and every bound is therefore inspectable, testable and, where an operator should own it,
 * settable from that feed's own settings.
 *
 * <p>Start from {@link #closed()} or {@link #soft()} - the two fail modes the signal families actually divide into -
 * and narrow with the withers, each named exactly like its accessor:
 * {@snippet :
 * FeedPolicy.closed().maxPages(10).requestTimeout(Duration.ofSeconds(15))
 * }
 *
 * <p><strong>Why the deadline exists.</strong> A per-request timeout does not bound a paginated fetch: fifty pages
 * at thirty seconds each is a twenty-five-minute call that still looks bounded on every individual request. The
 * {@link #deadline()} bounds the whole fetch - every page and every retry together - so a single-flight refresh can
 * never park a scheduler behind a slow-but-not-hung feed.
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

    /**
     * What a feed's consumer must see when the feed cannot answer. The two modes are a property of the <em>signal</em>,
     * not of the vendor: a missing advisory answer would read as "this package is clean" and must therefore fail
     * closed, while a missing exploit-probability score or maintainer-health score only leaves a ranking aid absent
     * and may fail soft.
     */
    public enum FailMode {
        /**
         * A failure is raised as a {@link FeedException}. The consumer never mistakes an error for an empty answer -
         * the mode every advisory and malicious-package feed uses.
         */
        CLOSED,
        /**
         * A failure degrades: the fetch answers {@link FeedClient.Status#DEGRADED} carrying the failure, the caller
         * keeps whatever it had before, and one warning is logged. The mode a ranking aid uses, where an absent
         * signal is a safe answer and blocking every publish on a vendor outage is not.
         */
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

    /**
     * The fail-closed default: 30 s per request, a 5 min whole-fetch deadline, 50 pages, 3 attempts with a 1 s
     * exponential backoff capped at 30 s, a 64 MiB response cap and a 16 MiB snapshot cap, refreshed every 6 hours
     * and retried after 15 minutes, refusing a cross-origin cursor. The numbers mirror what the hand-rolled feed
     * clients converged on, so adopting the client is not also a behaviour change.
     */
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

    /** {@link #closed()}'s bounds with {@link FailMode#SOFT} - the ranking-aid shape. */
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

    /**
     * Whether a pagination cursor must stay on the first request's origin. Leave it on: a vendor that hands back an
     * absolute cursor pointing elsewhere is either compromised or being impersonated, and the request's credential
     * header would travel with it. Turn it off only for a deployment whose own mirror legitimately paginates across
     * hosts, and say why where the policy is built.
     */
    public FeedPolicy sameOriginOnly(boolean value) {
        return new FeedPolicy(failMode, requestTimeout, deadline, maxPages, maxAttempts, backoff, backoffMultiplier,
                maxBackoff, maxResponseBytes, maxSnapshotBytes, refreshInterval, retryInterval, value);
    }

    /**
     * The delay before {@code attempt} (1-based, so attempt 2 is the first retry): the vendor's {@code Retry-After}
     * when it named one, else an exponential backoff from {@link #backoff()}, both capped by {@link #maxBackoff()}.
     * Deterministic by design - no jitter - so a test asserts the exact schedule; a caller that must spread a fleet's
     * refreshes does so where it schedules them, not inside a single-flight fetch.
     */
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
