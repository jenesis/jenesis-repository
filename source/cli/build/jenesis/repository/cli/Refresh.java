package build.jenesis.repository.cli;

import module java.base;

/**
 * {@code --refresh}: reprint a status on an interval until the work it describes reaches a terminal state.
 *
 * <p>No surface of this product blocks on long work: a command that starts it returns at once, and a caller who wants
 * to watch asks to. A bare {@code --refresh} polls at the cadence of what is watched - a single number would be wrong
 * for both the fast case and the slow one - and {@code --refresh=10s} names one.
 *
 * <p>Under {@code --json} it polls internally and prints the final state once: {@link Output#forget} drops each
 * intermediate response, so stdout stays one value. Only commands whose work outlives the request take the flag.
 */
final class Refresh {

    /** How long a poll waits when the caller named no interval and the command named no cadence of its own. */
    private static final Duration FALLBACK = Duration.ofSeconds(5);

    /** The floor: no watched state changes faster. */
    private static final Duration FLOOR = Duration.ofSeconds(1);

    /** How long a watch runs before it gives up and prints what it last saw, rather than hanging for ever. */
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

    /** Whether {@code --refresh} was asked for and the command that ran had nothing to watch. */
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
