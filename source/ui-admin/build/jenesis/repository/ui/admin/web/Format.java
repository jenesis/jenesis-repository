package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.ui.DurationWords;
import org.springframework.stereotype.Component;

/**
 * Small formatting helper exposed to Thymeleaf as {@code @format} (e.g. {@code ${@format.bytes(n)}}).
 */
@Component("format")
public class Format {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};

    /** {@code Math.log(1024)}, the unit divisor. */
    private static final double LOG_1024 = Math.log(1024);

    public String bytes(long value) {
        if (value <= 0) {
            return "0 B";
        }
        int unit = (int) (Math.log(value) / LOG_1024);
        unit = Math.min(unit, UNITS.length - 1);
        if (unit == 0) {
            return value + " B";
        }
        double scaled = value / Math.pow(1024, unit);
        return String.format(Locale.ROOT, "%.1f %s", scaled, UNITS[unit]);
    }

    /** A gate verdict as a person reads it, by the name the settings give it: {@code QUARANTINE} as "Hold for
     *  review"; a value no verdict names as it is. */
    public String verdict(String value) {
        if (value == null) {
            return "";
        }
        return Setting.Choice.VERDICTS.stream().filter(choice -> choice.value().equals(value.trim()))
                .map(Setting.Choice::name).findFirst().orElse(value);
    }

    /** A stored duration in words, as every setting's is shown: "30 days", "never"; an empty one as a dash. */
    public String duration(String value) {
        return value == null || value.isBlank() ? "-" : DurationWords.describe(value);
    }
}
