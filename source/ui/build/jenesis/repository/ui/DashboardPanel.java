package build.jenesis.repository.ui;

import module java.base;

/**
 * One panel of the landing dashboard, contributed by a {@link DashboardContributor}: a title opening the screen it is
 * about, a headline figure with a caption, a few lines beside it, and a note saying how current it is.
 *
 * @param title      what the panel is about, linking to {@code href}
 * @param href       the screen the panel opens, an application path ({@code /ui/repositories})
 * @param figure     the headline figure as it reads ({@code 12}, {@code 3.2 GiB}), or empty for none
 * @param caption    what the figure counts ({@code repositories}), or empty
 * @param tone       whether the figure asks for attention
 * @param lines      a few further figures, each with its own link or none
 * @param note       how current the panel is, or why it says nothing ({@code as of 12:03}), or empty
 * @param refreshing whether a count behind the panel is under way, so the dashboard asks again shortly
 */
public record DashboardPanel(String title, String href, String figure, String caption, Tone tone, List<Line> lines,
                             String note, boolean refreshing) {

    /** How many lines a panel shows; a longer list belongs on the screen it opens. */
    public static final int LINES = 5;

    public DashboardPanel {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(href, "href");
        figure = figure == null ? "" : figure;
        caption = caption == null ? "" : caption;
        tone = tone == null ? Tone.NEUTRAL : tone;
        lines = List.copyOf(lines.subList(0, Math.min(lines.size(), LINES)));
        note = note == null ? "" : note;
    }

    /** A panel of one figure and its lines, current as it is read. */
    public DashboardPanel(String title, String href, String figure, String caption, Tone tone, List<Line> lines) {
        this(title, href, figure, caption, tone, lines, "", false);
    }

    /** Whether the figure is quiet, asks for attention, or says all is clear. */
    public enum Tone { NEUTRAL, ATTENTION, CLEAR }

    /** A further figure: a label, its value and the screen it opens, or {@code null} for none. */
    public record Line(String label, String value, String href) {

        public Line {
            Objects.requireNonNull(label, "label");
            value = value == null ? "" : value;
        }
    }
}
