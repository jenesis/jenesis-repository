package build.jenesis.repository.icon;

import module java.base;

/**
 * One resolved mark: the inline SVG a surface draws for a contributor, the name the row is attributed to, and which of
 * three answers this is - without the kind, "declares none" and "is gone" would collapse into one.
 * <ul>
 *   <li>{@link Kind#DECLARED} - installed, and the document is the contributor's own, byte for byte.</li>
 *   <li>{@link Kind#GENERATED} - installed with no mark, so the document is derived from its name
 *       ({@link Marks#generated}): a stable per-contributor identity, not a placeholder.</li>
 *   <li>{@link Kind#ORPHANED} - <b>nothing answers to this name</b>. A finding outlives an uninstalled plug-in, so the
 *       name is all there is; the figure is the one it would have generated, inside a <em>dashed</em> tile.</li>
 * </ul>
 *
 * <p><b>Colour is never the only cue.</b> The dashed tile carries the distinction in the drawing, and {@link #kind()},
 * {@link #installed()} and {@link #title()} carry it into markup for every reader a colour never reaches.
 *
 * @param name the contributor this mark stands for; the attribution key, and for a generated or orphaned mark the sole
 *     input to the figure
 * @param kind which of the three answers this is
 * @param svg the inline SVG document to render, always non-blank
 */
public record Mark(String name, Kind kind, String svg) {

    /** Which of the three answers a resolved mark is. */
    public enum Kind {

        /** The contributor is installed and declared a mark of its own. */
        DECLARED,

        /** The contributor is installed and declares no mark, so its mark is derived from its name. */
        GENERATED,

        /** No contributor answers to this name on this deployment; only the recorded name survives. */
        ORPHANED
    }

    public Mark {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(svg, "svg");
        if (svg.isBlank()) {
            throw new IllegalArgumentException("a resolved mark is never blank: " + name);
        }
    }

    /** The palette bucket this mark is tinted with, or empty for a {@link Kind#DECLARED} one: a declared mark is the
     *  contributor's own drawing, and tinting someone's logo alters it. Only computed figures carry a colour, and the
     *  figures already differ by shape, so a monochrome display or a screen reader loses nothing. */
    public OptionalInt tint() {
        return kind == Kind.DECLARED ? OptionalInt.empty() : OptionalInt.of(Marks.tint(name));
    }

    /** Whether a contributor answering to {@link #name()} is on this deployment - false only for
     *  {@link Kind#ORPHANED}. */
    public boolean installed() {
        return kind != Kind.ORPHANED;
    }

    /** A short text label for where a drawing cannot be read - a {@code title}, an {@code aria-label}, a tooltip. It
     *  names the contributor and says when nothing answers to that name, so the distinction survives without colours or
     *  images. */
    public String title() {
        return installed() ? name : name + " (not installed)";
    }
}
