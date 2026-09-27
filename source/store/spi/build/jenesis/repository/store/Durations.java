package build.jenesis.repository.store;

import module java.base;

/**
 * The one duration grammar a deployment may write, wherever it writes it.
 *
 * <p>Two grammars would disagree in the direction that wastes an operator's afternoon: a reader of the environment
 * accepting the suffixed style an environment variable naturally carries - {@code 6h}, {@code 90s},
 * {@code 500ms} - while the console or {@code PUT /api/config} validated with a bare {@code Duration.parse}, which
 * refuses every suffixed form, so {@code JENREG_<KEY>=6h} would be honoured while typing {@code 6h} into the
 * settings screen was not. The suffixed style is what an environment variable carries, so the grammar takes both
 * ISO-8601 and suffixed forms, and it is stated here once, in the module both the validator and the readers can
 * see.
 *
 * <p>It lives beside {@link Features} in the store SPI - the module every other one already requires - rather than
 * in the settings catalogue, because the store's own dials read it too: the rebuild driver's cadence, the proxy's
 * request timeout and negative-cache window, the collector's grace, a credential's lifetime - none of which may
 * carry a parser of its own.
 *
 * <p>A bare number is refused everywhere, deliberately: Spring's relaxed binding reads {@code 30} as milliseconds,
 * an operator who writes it usually means seconds, and a dial that guesses is wrong by a factor of a thousand in
 * silence. The one place a bare number is unambiguous is a key whose unit is in its name ({@code *-millis}), and
 * that case is read before this grammar is asked, by the dial that owns the key.
 */
public final class Durations {

    /** The word a duration rule that may be inherited takes to be switched off instead: where an unset value means
     *  "the wider level's", {@code none} means "no rule here". Only a reader that honours it accepts it
     *  ({@link #parseOrNone}); every other duration dial refuses it like any other word. */
    public static final String NONE = "none";

    private Durations() {
    }

    /** {@code value} as a duration, or empty for {@link #NONE} - the rule switched off. */
    public static Optional<Duration> parseOrNone(String value) {
        Objects.requireNonNull(value, "value");
        return NONE.equals(value.trim()) ? Optional.empty() : Optional.of(parse(value));
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
