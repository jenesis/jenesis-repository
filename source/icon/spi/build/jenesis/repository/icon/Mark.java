package build.jenesis.repository.icon;

import module java.base;

/**
 * One resolved mark: the inline SVG document a surface draws for a contributor, the name it attributes the row to,
 * and <em>which of the three answers this is</em>. The kind is the point of the type - without it a console can
 * render a mark but cannot say anything about it, and the two states that most need distinguishing collapse into
 * one.
 *
 * <p>The three kinds are deliberately not two:
 * <ul>
 *   <li>{@link Kind#DECLARED} - the contributor is installed and shipped a mark of its own; the document is that
 *       contributor's, byte for byte.</li>
 *   <li>{@link Kind#GENERATED} - the contributor is installed and declares no mark, so the document is the
 *       deterministic one derived from its name ({@link Marks#generated}). It is a real, stable, per-contributor
 *       identity, not a placeholder: the same plug-in draws the same figure everywhere.</li>
 *   <li>{@link Kind#ORPHANED} - <b>nothing answers to this name any more</b>. The row survives its contributor -
 *       a finding records which plug-in produced it, and that plug-in can be uninstalled - so the name is all there
 *       is. The figure is the same one the contributor would have generated, inside a <em>dashed</em> tile, which is
 *       what keeps "declares none" and "is gone" apart.</li>
 * </ul>
 *
 * <p><b>Colour is never the only cue.</b> A console is free to tint an orphaned mark, and that treatment is its
 * business, but this type never requires it to: the dashed tile carries the distinction in the drawing itself, and
 * {@link #kind()}, {@link #installed()} and {@link #title()} carry it in the markup - a {@code title} or
 * {@code aria-label} attribute, a badge, a filter - for every reader a colour never reaches.
 *
 * @param name the contributor this mark stands for; the attribution key, and for a generated or orphaned mark the
 *             sole input to the figure
 * @param kind which of the three answers this is
 * @param svg  the inline SVG document to render, always non-blank
 */
public record Mark(String name, Kind kind, String svg) {

    /** Which of the three answers a resolved mark is. See the type javadoc: the last two never collapse into one. */
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

    /** Whether a contributor answering to {@link #name()} is on this deployment - false only for {@link
     *  Kind#ORPHANED}, so a surface can screen or badge orphaned rows without reading the kind. */
    public boolean installed() {
        return kind != Kind.ORPHANED;
    }

    /** A short text label a surface puts where a drawing cannot be read - a {@code title}, an {@code aria-label}, a
     *  tooltip. It names the contributor and, when nothing answers to that name any more, says so, so the
     *  installed/orphaned distinction survives with the colours and the images switched off. */
    public String title() {
        return installed() ? name : name + " (not installed)";
    }
}
