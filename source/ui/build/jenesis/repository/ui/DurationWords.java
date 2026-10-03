package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.store.Durations;

/**
 * A duration as the console says it: "30 days", "6 hours", "never". The settings store, the API and the command line
 * keep the machine forms ({@code P30D}, {@code 6h}, {@code none}); a console reads them in words, in the largest unit
 * that states the duration exactly. Every screen shows a duration through it, as {@code durations} in a template.
 */
public final class DurationWords {

    /** The instance a template reaches as {@code durations}. */
    public static final DurationWords DISPLAY = new DurationWords();

    private DurationWords() {
    }

    /** {@code value} in words - a {@link Duration}, or a duration as the settings write it - and {@code -} for none. */
    public String words(Object value) {
        return switch (value) {
            case null -> "-";
            case Duration duration -> describe(duration);
            case String raw when raw.isBlank() -> "-";
            case String raw -> describe(raw);
            default -> String.valueOf(value);
        };
    }

    /** How long something took, in words rounded to what a reader takes in: milliseconds under a second, whole
     *  seconds under a minute, then minutes and seconds, then hours and minutes. */
    public String measured(Duration took) {
        if (took == null) {
            return "-";
        }
        if (took.compareTo(Duration.ofSeconds(1)) < 0) {
            return count(took.toMillis(), "millisecond");
        }
        long seconds = Math.round(took.toMillis() / 1000.0);
        if (seconds < 60) {
            return count(seconds, "second");
        }
        if (seconds < 3600) {
            return count(seconds / 60, "minute") + (seconds % 60 == 0 ? "" : " " + count(seconds % 60, "second"));
        }
        long minutes = seconds / 60;
        return count(minutes / 60, "hour") + (minutes % 60 == 0 ? "" : " " + count(minutes % 60, "minute"));
    }

    /** {@code raw} in words, or {@code raw} itself where it is not a duration the grammar reads. */
    public static String describe(String raw) {
        if (raw.trim().equals(Durations.NONE)) {
            return "never";
        }
        Duration duration;
        try {
            duration = Durations.parse(raw);
        } catch (IllegalArgumentException _) {
            return raw;
        }
        return describe(duration);
    }

    /** {@code duration} in words: whole days, else hours, minutes, seconds or milliseconds. */
    public static String describe(Duration duration) {
        if (duration.isZero()) {
            return "0 seconds";
        }
        long millis = duration.toMillis();
        if (millis % Duration.ofDays(1).toMillis() == 0) {
            return count(duration.toDays(), "day");
        }
        if (millis % Duration.ofHours(1).toMillis() == 0) {
            return count(duration.toHours(), "hour");
        }
        if (millis % Duration.ofMinutes(1).toMillis() == 0) {
            return count(duration.toMinutes(), "minute");
        }
        if (millis % 1000 == 0) {
            return count(duration.toSeconds(), "second");
        }
        return count(millis, "millisecond");
    }

    private static String count(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
