package build.jenesis.repository.cli;

import module java.base;

/**
 * {@code --refresh}: reprint a status on an interval until the work it describes reaches a terminal state.
 *
 * <p><b>Why the CLI has this at all.</b> No operator surface in this product blocks or times out - a screen
 * renders what is known at once and refreshes itself, and an API answers with a status document rather than a held
 * connection. The CLI is the third surface and follows the same rule from the other side: a command that starts
 * long work returns immediately, and a caller who wants to watch it asks to watch it. The alternative - a command
 * that holds a socket open until a walk of ten million objects finishes - fails on exactly the deployment where
 * the watching matters, and gives a caller nothing to look at in the meantime.
 *
 * <p><b>The interval is taken from what is being watched, never from one constant.</b> A bare {@code --refresh}
 * uses the cadence the thing itself moves at - the half minute a requested walk takes to be picked up, the few
 * seconds an import job's counters advance in - because a single number is wrong at both ends: it wastes requests
 * on something that changes hourly and misses everything that changes in a second. {@code --refresh=10s} says it
 * outright when the caller knows better.
 *
 * <p><b>It does not break {@code --json}, which promises exactly one JSON value.</b> A refreshing command polls
 * internally and prints the final state once: {@link Output#forget} drops each intermediate response as the next
 * is taken, so a hundred polls still leave one document to flush. That is what a script wants anyway - the
 * intermediate states are for a person watching, and a person is who the text mode is for.
 *
 * <p><b>Not every command grows the flag.</b> A point read has nothing to watch and a setting write is finished
 * when it returns; offering to refresh those would be an interface promising something it cannot mean. The
 * commands that take it are the ones with work that outlives the request, and each names its own cadence.
 */
final class Refresh {

    /** How long a poll waits when the caller named no interval and the command named no cadence of its own. */
    private static final Duration FALLBACK = Duration.ofSeconds(5);

    /** The floor: below this the tool is asking faster than any of these states can change, so it is asking the
     *  server to say "no" more often rather than learning anything sooner. */
    private static final Duration FLOOR = Duration.ofSeconds(1);

    /** How long a watch runs before it gives up and prints what it last saw. Work that outlives this is work a
     *  person should stop watching and come back to; the command says so rather than hanging for ever. */
    private static final Duration PATIENCE = Duration.ofHours(2);

    private static Duration asked;
    private static boolean requested;
    private static boolean watched;

    private Refresh() {
    }

    /** Record {@code --refresh} or {@code --refresh=<interval>} from the command line. */
    static void requested(String flag) {
        requested = true;
        int equals = flag.indexOf('=');
        asked = equals < 0 ? null : parse(flag.substring(equals + 1));
    }

    /** Forget the mode, so one invocation in a JVM cannot leak into the next - the reason {@link Output} does. */
    static void reset() {
        asked = null;
        requested = false;
        watched = false;
    }

    /** Whether the command that ran actually watched anything. A caller who asked to watch a command that has
     *  nothing to watch is told so, rather than left believing a flag did something: the flag is global, so it is
     *  accepted everywhere and meaningful only where work outlives the request. */
    static boolean unwatched() {
        return requested && !watched;
    }

    /** Whether this run was asked to watch. */
    static boolean on() {
        return requested;
    }

    /**
     * Watch {@code poll} until it reports a terminal state, or until patience runs out.
     *
     * <p>Each poll prints in text mode and is forgotten in JSON mode, so what a program reads at the end is the
     * last state and nothing else. Returns what the final poll returned.
     *
     * @param cadence what this subject moves at, used unless the caller named an interval.
     */
    static int until(Duration cadence, Poll poll) throws Exception {
        watched = true;
        Duration every = interval(cadence);
        Instant deadline = Instant.now().plus(PATIENCE);
        while (true) {
            if (Output.isJson()) {
                Output.forget();   // only the last one survives, so stdout stays exactly one JSON value
            }
            Poll.State state = poll.once();
            if (state.terminal() || Instant.now().isAfter(deadline)) {
                return state.code();
            }
            Thread.sleep(every.toMillis());
        }
    }

    /** The interval to use: what the caller asked for, else what the subject moves at, else the fallback. */
    private static Duration interval(Duration cadence) {
        Duration chosen = asked != null ? asked : cadence != null ? cadence : FALLBACK;
        return chosen.compareTo(FLOOR) < 0 ? FLOOR : chosen;
    }

    /** {@code 30s}, {@code 5m}, {@code 2h}, or an ISO-8601 duration - the spellings a person actually types. */
    private static Duration parse(String value) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            if (trimmed.startsWith("p")) {
                return Duration.parse(trimmed);
            }
            char unit = trimmed.charAt(trimmed.length() - 1);
            long amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
            return switch (unit) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> throw new IllegalArgumentException("unit");
            };
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("--refresh takes an interval like 30s, 5m, 2h or PT30S, not '"
                    + value + "'");
        }
    }

    /** One reading of the thing being watched. */
    @FunctionalInterface
    interface Poll {

        /** Read and print the current state. */
        State once() throws Exception;

        /**
         * What one reading found: the exit code it would give, and whether there is any point asking again.
         *
         * @param code     the exit code this state deserves, if it is the last one.
         * @param terminal whether the work has finished, one way or the other.
         */
        record State(int code, boolean terminal) {

            static State running() {
                return new State(0, false);
            }

            static State done(int code) {
                return new State(code, true);
            }
        }
    }
}
