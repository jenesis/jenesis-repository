package build.jenesis.repository.compliance;

import module java.base;

/**
 * Refreshing every mirroring advisory feed, and saying which ones did not come back.
 *
 * <p>Two surfaces draw the feeds before they report on a repository - the console's rescan and the API's explicit
 * refresh - and each had written the loop itself. They had drifted into opposite failure behaviour over the same
 * SPI: the API logged a warning per feed it could not draw, and the console caught the same exceptions into an
 * empty block, so one deployment surface said nothing at all about a feed that never loaded. That is the shape
 * &sect;2 exists to prevent, and the reason this is one helper rather than a tidier copy of either loop.
 *
 * <p><b>The exception a draw throws is ours, not the vendor's.</b> {@link RefreshableSource#refresh} documents its
 * {@code IOException} as "a wiring or infrastructure fault, never a vendor outage, which is fail-soft and shows up
 * in the returned {@link Freshness} instead". So catching it and continuing is right - a report over the data there
 * is beats no report - and discarding it is not: it is the one class of failure the contract says belongs to this
 * deployment rather than to a third party.
 *
 * <p><b>Freshness is read as well, because a draw can fail without throwing.</b> The contract offers the returned
 * value precisely "so the caller can tell a refresh that landed from one that did not without reading a second
 * accessor", and a source that has never fetched reports no instant - the {@code NEVER} sentinel, whose documented
 * meaning is that the emptiness confirms nothing. Both are worth a row for the same reason.
 *
 * <p><b>Why the answer is rows of text.</b> A caller puts them in a stored report, whose rows are free text, and
 * reads them back on a later request to render beside the result they qualify. So the two ends need one agreement about which rows are these rows, and {@link #WARNING} is it -
 * written by {@link #refreshAll} and recognised by {@link #warningsIn}, rather than the same literal typed at
 * both ends of two modules.
 */
public final class FeedRefresh {

    private static final System.Logger LOGGER = System.getLogger(FeedRefresh.class.getName());

    /** The prefix every row this produces begins with, so a reader can pick them out of a report's other lines. */
    public static final String WARNING = "advisory feed ";

    private FeedRefresh() {
    }

    /**
     * Draw every mirroring feed among {@code signals}, and answer one row per feed that did not come back.
     *
     * <p>Never throws: a feed that cannot be drawn leaves the report standing, which is what makes this callable
     * from a read surface. The rows are the record that it happened.
     */
    public static List<String> refreshAll(List<AdvisorySignal> signals) {
        List<String> unrefreshed = new ArrayList<>();
        for (AdvisorySignal signal : signals) {
            if (signal instanceof RefreshableSource mirror) {
                try {
                    if (mirror.refresh().refreshed().isEmpty()) {
                        unrefreshed.add(WARNING + "'" + signal.name()
                                + "' has never fetched - its emptiness confirms nothing");
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Could not draw the " + signal.name() + " advisory feed; what is reported was read from "
                                    + "whatever it last held", e);
                    unrefreshed.add(WARNING + "'" + signal.name() + "' could not be refreshed ("
                            + e.getClass().getSimpleName() + ") - this scan read whatever it last held");
                }
            }
        }
        return List.copyOf(unrefreshed);
    }

    /** The rows of a stored report that {@link #refreshAll} wrote, picked out of whatever else it carries. */
    public static List<String> warningsIn(List<String> rows) {
        return rows.stream().filter(row -> row.startsWith(WARNING)).toList();
    }
}
