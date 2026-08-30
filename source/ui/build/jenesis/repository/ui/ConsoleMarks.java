package build.jenesis.repository.ui;

import build.jenesis.repository.icon.Mark;

/**
 * The console's presentation of a {@link Mark} - today the one thing every render site needs and no two of them may
 * spell differently: the stylesheet class a computed mark's tint bucket maps to.
 *
 * <p>It lives here rather than on {@link Mark} because a CSS class name is the console's business and not the icon
 * SPI's, and it lives in one place rather than at each render site because it was a private helper at the first one
 * and a second was about to copy it. A class name duplicated across render sites is the shape that drifts silently:
 * both halves keep compiling, both keep rendering, and the only symptom is a mark somewhere that quietly stops being
 * tinted.
 */
final class ConsoleMarks {

    private ConsoleMarks() {
    }

    /**
     * The stylesheet class for a computed mark's tint, or the empty string for a contributor's own drawing - which is
     * never tinted, because altering somebody's logo is what every brand guideline forbids. {@link Mark#tint} already
     * answers empty for a declared mark, so that rule is honoured by construction here rather than restated.
     *
     * <p>A class rather than an inline {@code style} attribute: a strict content-security policy blocks inline
     * styles, and a class also keeps the palette in the stylesheet where a theme can redefine it per colour scheme.
     */
    static String tint(Mark mark) {
        return mark.tint().stream()
                .mapToObj(bucket -> String.format("app-mark--t%02d", bucket))
                .findFirst()
                .orElse("");
    }
}
