package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.store.Durations;

/**
 * A duration as the console says it: "30 days", "6 hours", "never". The settings store, the API and the command line
 * keep the machine forms ({@code P30D}, {@code 6h}, {@code none}); a console reads them in words, in the largest unit
 * that states the duration exactly.
 */
public final class DurationWords {

    private DurationWords() {
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
