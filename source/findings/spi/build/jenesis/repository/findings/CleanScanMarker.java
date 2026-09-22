package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.Severity;

/**
 * The negative "scanned clean at T" marker the vulnerability report persists for a coordinate the advisory feeds
 * reported nothing for, so a known-clean coordinate is served from the durable ledger rather than triggering a full
 * live rescan of every feed on every read. Without it a clean coordinate stores no row, so {@code stored.isEmpty()}
 * stays true forever and each render re-queries the feeds - a read-first violation (§7): the reader pays for a scan a
 * prior read already did.
 *
 * <p>The marker is an ordinary categorize-never-discard finding of {@link Finding.Kind#CLEAN} under the reserved
 * {@code (source, id)} this contract fixes, so it rides the same ledger key the coordinate's other findings do and an
 * eviction reclaims it with them. It carries no severity and is never rendered as a vulnerability - the report's
 * advisory view keys off {@link Finding.Kind#VULNERABILITY}/{@link Finding.Kind#MALWARE} only. A re-scan that finds the
 * coordinate still clean re-records the marker, refreshing its {@code lastSeen} and so sliding the freshness window
 * forward; the first advisory that does land is a real finding checked <em>before</em> the marker, so a coordinate that
 * turns vulnerable is served from its advisory rows and the stale marker is simply ignored.
 */
public final class CleanScanMarker {

    private CleanScanMarker() {
    }

    /** The feed-neutral source the clean marker is attributed to - a scan result, not any one feed. */
    public static final String SOURCE = "scan";

    /** The reserved finding id the clean marker occupies on a coordinate. */
    public static final String ID = "clean";

    /** The default freshness window: within this of a recorded clean scan the coordinate is served from the ledger; past
     *  it the feeds are consulted again. A conservative default - the reader is spared a rescan for a day, and a newly
     *  disclosed advisory lands on the next window. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /** The marker finding recorded when the feeds report nothing for a coordinate, seen now on both ends. */
    public static Finding of(Instant now) {
        return Finding.of(ID, SOURCE, Finding.Kind.CLEAN, "scan", Severity.NONE,
                "No advisories reported by the configured feeds.", now);
    }

    /** Whether {@code stored} (a coordinate's ledger rows) carries a clean-scan marker still fresh at {@code now}: a
     *  {@link Finding.Kind#CLEAN} row from this source, not superseded, whose last scan is within {@code ttl} of now.
     *  A stale (or superseded, or absent) marker answers false, so the caller re-queries the feeds. */
    public static boolean fresh(List<Finding> stored, Instant now, Duration ttl) {
        Instant floor = now.minus(ttl);
        for (Finding finding : stored) {
            if (finding.kind() == Finding.Kind.CLEAN && SOURCE.equals(finding.source()) && finding.active()
                    && finding.lastSeen().isAfter(floor)) {
                return true;
            }
        }
        return false;
    }
}
