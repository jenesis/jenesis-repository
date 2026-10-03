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
 * @param action     where what the panel counts begins, offered while it has none ("New repository"), or empty
 * @param note       what else to say of the panel's figures - a count under way, or why the last one failed - or empty
 * @param asOf       when the figures were counted, which the page shows in the reader's timezone, or empty where they
 *                   are read as the page is
 * @param refreshing whether a count behind the panel is under way, so the dashboard asks again shortly
 */
public record DashboardPanel(String title, String href, String figure, String caption, Tone tone, List<Line> lines,
                             Optional<Link> action, String note, Optional<Instant> asOf, boolean refreshing) {

    /** How many lines a panel shows; a longer list belongs on the screen it opens. */
    public static final int LINES = 5;

    public DashboardPanel {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(href, "href");
        figure = figure == null ? "" : figure;
        caption = caption == null ? "" : caption;
        tone = tone == null ? Tone.NEUTRAL : tone;
        lines = List.copyOf(lines.subList(0, Math.min(lines.size(), LINES)));
        action = action == null ? Optional.empty() : action;
        note = note == null ? "" : note;
        asOf = asOf == null ? Optional.empty() : asOf;
    }

    /** A panel of one figure and its lines, current as it is read. */
    public DashboardPanel(String title, String href, String figure, String caption, Tone tone, List<Line> lines) {
        this(title, href, figure, caption, tone, lines, Optional.empty(), "", Optional.empty(), false);
    }

    /** A panel counting {@code count} of {@code noun}: the figure, grouped by thousands and marked where the count
     *  stopped short ({@code capped}), and the noun agreeing with it as the caption. */
    public static DashboardPanel counted(String title, String href, long count, boolean capped, Noun noun, Tone tone,
                                         List<Line> lines) {
        return new DashboardPanel(title, href, Noun.figure(count, capped), noun.of(count, capped), tone, lines);
    }

    /** This panel, offering {@code where} what it counts begins. */
    public DashboardPanel offering(Link where) {
        return new DashboardPanel(title, href, figure, caption, tone, lines, Optional.of(where), note, asOf,
                refreshing);
    }

    /** Whether the panel has something to say: a figure, a line, or a verdict - all clear, or attention - in its
     *  caption. A panel with none of them is not drawn. */
    public boolean says() {
        return !figure.isEmpty() || !lines.isEmpty() || action.isPresent()
                || (tone != Tone.NEUTRAL && !caption.isEmpty());
    }

    /** What a figure counts, as it reads beside one and beside many - "version waits for a decision", "versions wait
     *  for a decision" - so the phrase agrees with its count wherever a panel says one. */
    public record Noun(String one, String many) {

        public Noun {
            Objects.requireNonNull(one, "one");
            Objects.requireNonNull(many, "many");
        }

        /** The phrase agreeing with {@code count}; a count that stopped short ({@code capped}) is many. */
        public String of(long count, boolean capped) {
            return count == 1 && !capped ? one : many;
        }

        /** The count and the phrase agreeing with it: "1 version", "12,000+ versions". */
        public String counted(long count, boolean capped) {
            return figure(count, capped) + " " + of(count, capped);
        }

        /** A count as a figure reads, grouped by thousands and marked where it stopped short. */
        public static String figure(long count, boolean capped) {
            return String.format(Locale.ROOT, "%,d", count) + (capped ? "+" : "");
        }
    }

    /** A way onward: what it says and the screen it opens. */
    public record Link(String label, String href) {

        public Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(href, "href");
        }
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
