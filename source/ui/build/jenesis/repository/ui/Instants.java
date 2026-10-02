package build.jenesis.repository.ui;

import module java.base;

/**
 * How a console screen shows an instant: to the second, in UTC, and saying so, rather than {@link Instant}'s nanosecond
 * {@code toString}. Screens reach it as the {@code instants} model attribute. The console's script rewrites every time
 * in this form into the reader's own timezone, so this form is what a page says without a script, and the one shape
 * the script looks for.
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
        String text = value.toString();
        try {
            return FORMAT.format(Instant.parse(text));
        } catch (DateTimeParseException _) {
            return text;
        }
    }
}
