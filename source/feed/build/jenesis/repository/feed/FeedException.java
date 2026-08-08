package build.jenesis.repository.feed;

import module java.base;

/**
 * The one named failure a bounded feed fetch raises, carrying <em>which</em> feed failed, <em>why</em> in a
 * machine-readable {@link Reason}, the HTTP status where there was one, how many attempts were spent, and the delay
 * the vendor asked for where it named one. Every bound this client enforces answers with one of these rather than
 * with a plausible-but-incomplete result: a pagination cap is {@link Reason#PAGE_CAP}, an over-long body is
 * {@link Reason#RESPONSE_CAP}, an over-budget fetch is {@link Reason#DEADLINE} (&sect;9, and the "bounds fail
 * visibly" gate).
 *
 * <p>It extends {@link IOException} so it travels the same path a feed's own I/O failure already travels, and so a
 * fail-closed consumer that catches {@code IOException} keeps failing closed.
 */
public final class FeedException extends IOException {

    /** Why a fetch failed - the difference between "try again shortly" and "this will not fix itself". */
    public enum Reason {
        /** The request could not be sent, or the connection failed or timed out. Retryable. */
        TRANSPORT,
        /** The feed answered a non-200. Retryable only for 429 and 5xx; a 401/403/404 will not fix itself. */
        STATUS,
        /** The feed advertised more pages than the policy's cap allows. Never retryable: the answer is unbounded. */
        PAGE_CAP,
        /** A response body exceeded the policy's byte cap. Never retryable. */
        RESPONSE_CAP,
        /** A completed snapshot exceeded the policy's snapshot cap and was not committed. Never retryable. */
        SNAPSHOT_CAP,
        /** The whole fetch ran past its deadline across pages and retries. Never retryable within this fetch. */
        DEADLINE,
        /** A pagination cursor pointed at another origin - an SSRF and a credential leak. Never retryable. */
        CROSS_ORIGIN,
        /** The reader could not make sense of an answer the feed did return. Never retryable. */
        MALFORMED,
        /** The calling thread was interrupted. Never retryable; the interrupt flag is restored. */
        INTERRUPTED
    }

    private final String feed;
    private final Reason reason;
    private final int status;
    private final int attempts;
    private final transient Duration retryAfter;

    FeedException(String feed, Reason reason, int status, int attempts, String detail, Throwable cause) {
        this(feed, reason, status, attempts, detail, cause, null);
    }

    FeedException(String feed,
                  Reason reason,
                  int status,
                  int attempts,
                  String detail,
                  Throwable cause,
                  Duration retryAfter) {
        super("The " + feed + " feed failed (" + reason + (status > 0 ? " " + status : "") + ") after "
                + attempts + (attempts == 1 ? " attempt" : " attempts") + ": " + detail, cause);
        this.feed = feed;
        this.reason = reason;
        this.status = status;
        this.attempts = attempts;
        this.retryAfter = retryAfter;
    }

    /** The feed's name - its attribution key, the same name its provider answers to. */
    public String feed() {
        return feed;
    }

    /** Why the fetch failed. */
    public Reason reason() {
        return reason;
    }

    /** The HTTP status when {@link Reason#STATUS}, otherwise {@code 0}. */
    public int status() {
        return status;
    }

    /** How many attempts were spent before giving up. */
    public int attempts() {
        return attempts;
    }

    /**
     * The delay the vendor's {@code Retry-After} asked for, when it sent one. A vendor that says how long its rate
     * limit lasts is obeyed in preference to guessing shorter, which is what exhausts a quota.
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Whether another attempt could plausibly succeed: a transport failure, a rate limit ({@code 429}) or a server
     * error ({@code 5xx}). A rejected credential, a missing entitlement, a malformed answer and every bound this
     * client enforces are <em>not</em> retryable - retrying them only burns quota and hides the cause.
     */
    public boolean retryable() {
        return switch (reason) {
            case TRANSPORT -> true;
            case STATUS -> status == 429 || status >= 500 && status <= 599;
            default -> false;
        };
    }
}
