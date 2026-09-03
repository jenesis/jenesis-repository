package build.jenesis.repository.store;

import module java.base;

/**
 * The one duration grammar a deployment may write, wherever it writes it.
 *
 * <p>There were two, and they disagreed in the direction that wastes an operator's afternoon. A cadence dial read
 * from the environment accepted the suffixed style an environment variable naturally carries - {@code 6h},
 * {@code 90s}, {@code 500ms} - and said so in its own rejection message: <em>"Accepted: an ISO-8601 duration (PT1H,
 * P1D) or a suffixed one (500ms, 90s, 5m, 6h, 2d)"</em>. The same key set through the console or
 * {@code PUT /api/config} was validated by a bare {@code Duration.parse}, which refuses every suffixed form. So the
 * product printed a grammar one of its own surfaces would not accept, and {@code JENREG_<KEY>=6h} was honoured while
 * typing {@code 6h} into the settings screen was not.
 *
 * <p>Two components each correct alone: the reader is permissive on purpose, the validator strict on purpose, and
 * neither knew about the other. The validator is the odd one out - the suffixed style is what an environment
 * variable carries - so the grammar widens rather than the message narrowing, and it is stated here once, in the
 * module both the validator and the readers can see.
 *
 * <p>It lives beside {@link Features} in the store SPI - the module every other one already requires - rather than
 * in the settings catalogue, because the free core's own dials read it too: the rebuild driver's cadence, the proxy's
 * request timeout and negative-cache window, the collector's grace, a credential's lifetime. When the grammar lived
 * in the enterprise catalogue, each of those carried a parser of its own, and three of them read a bare number as
 * seconds while the catalogue refused it.
 *
 * <p>A bare number is refused everywhere, deliberately: Spring's relaxed binding reads {@code 30} as milliseconds,
 * an operator who writes it usually means seconds, and a dial that guesses is wrong by a factor of a thousand in
 * silence. The one place a bare number is unambiguous is a key whose unit is in its name ({@code *-millis}), and
 * that case is read before this grammar is asked, by the dial that owns the key.
 */
public final class Durations {

    private Durations() {
    }

    /**
     * {@code value} as a duration: ISO-8601 ({@code PT1H}, {@code P1D}) or suffixed ({@code 500ms}, {@code 90s},
     * {@code 5m}, {@code 6h}, {@code 2d}).
     *
     * @throws IllegalArgumentException if it is neither - including a bare number, whose unit the product
     *                                  deliberately does not agree on.
     */
    public static Duration parse(String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        try {
            return Duration.parse(trimmed);
        } catch (DateTimeParseException _) {
            return suffixed(trimmed);
        }
    }

    /** Whether {@link #parse} would accept {@code value} - the validator's question, asked without the throw. */
    public static boolean parses(String value) {
        try {
            parse(value);
            return true;
        } catch (RuntimeException _) {
            return false;
        }
    }

    /** {@code 500ms} / {@code 90s} / {@code 5m} / {@code 6h} / {@code 2d}. Only ASCII digits count:
     *  {@code Character.isDigit} and {@code Long.parseLong} both accept every Unicode decimal digit, so an
     *  Arabic-Indic {@code ٦h} would otherwise resolve to six hours from a value no operator can read back. */
    private static Duration suffixed(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Not a duration: " + value);
        }
        char first = value.charAt(0);
        int start = first == '-' || first == '+' ? 1 : 0;
        int digits = start;
        while (digits < value.length() && value.charAt(digits) >= '0' && value.charAt(digits) <= '9') {
            digits++;
        }
        if (digits == start) {
            throw new IllegalArgumentException("Not a duration: " + value);
        }
        long amount = Long.parseLong(value.substring(0, digits));
        return switch (value.substring(digits).toLowerCase(Locale.ROOT)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Not a duration: " + value);
        };
    }
}
