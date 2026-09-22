package build.jenesis.repository.ui.admin.web;

import module java.base;
import org.springframework.stereotype.Component;

/**
 * Small formatting helper exposed to Thymeleaf as {@code @format} (e.g. {@code ${@format.bytes(n)}}).
 */
@Component("format")
public class Format {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};

    /** {@code Math.log(1024)}, the unit divisor, computed once rather than per rendered size (this runs per row). */
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
}
