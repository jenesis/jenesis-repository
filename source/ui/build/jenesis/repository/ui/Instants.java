package build.jenesis.repository.ui;

import module java.base;

/**
 * How a console screen shows an instant: to the second, in UTC, and saying so, rather than {@link Instant}'s nanosecond
 * {@code toString}. Screens reach it as the {@code instants} model attribute and draw a time through the
 * {@code base :: time} fragment - a {@code <time>} element carrying {@link #iso} as its {@code datetime}, which the
 * console's script shows in the reader's own timezone. This form is what the element says without a script.
 */
public final class Instants {

    /** The one instance the console's screens share; it holds no state. */
    public static final Instants DISPLAY = new Instants();

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private Instants() {
    }

    /** {@code value} as a screen shows it: an {@link Instant}, or text in ISO-8601 instant form, to the second in UTC;
     *  any other text as it is; nothing as the empty string. */
    public String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Instant instant) {
            return FORMAT.format(instant);
        }
        return instant(value).map(FORMAT::format).orElseGet(value::toString);
    }

    /** {@code value} as a machine reads it - ISO-8601 to the second, in UTC - where it is an instant, or {@code null}
     *  where it is not, which leaves a {@code <time>} element without the {@code datetime} a script would convert. */
    public String iso(Object value) {
        return value == null ? null
                : instant(value).map(at -> at.truncatedTo(ChronoUnit.SECONDS).toString()).orElse(null);
    }

    private static Optional<Instant> instant(Object value) {
        if (value instanceof Instant instant) {
            return Optional.of(instant);
        }
        try {
            return Optional.of(Instant.parse(value.toString()));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }
}
