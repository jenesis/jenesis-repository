package build.jenesis.repository.ui;

import java.util.Locale;

import build.jenesis.repository.icon.Mark;

/**
 * The console's presentation of a {@link Mark}: the stylesheet class a computed mark's tint maps to, shared by every
 * render site. A CSS class is the console's concern rather than the icon SPI's.
 */
final class ConsoleMarks {

    private ConsoleMarks() {
    }

    /**
     * The stylesheet class for a computed mark's tint, or empty for a contributor's own drawing, which is never tinted
     * ({@link Mark#tint} is empty for it). A class rather than an inline style, which a strict content-security policy
     * blocks, and so a theme can redefine the palette.
     */
    static String tint(Mark mark) {
        return mark.tint().stream()
                .mapToObj(bucket -> String.format(Locale.ROOT, "app-mark--t%02d", bucket))
                .findFirst()
                .orElse("");
    }
}
