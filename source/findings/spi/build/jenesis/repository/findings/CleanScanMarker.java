package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.Severity;

/**
 * The "scanned clean at T" marker the vulnerability report persists for a coordinate the feeds reported nothing for, so
 * a known-clean coordinate is served from the ledger rather than re-querying every feed on every read.
 *
 * <p>It is a {@link Finding.Kind#CLEAN} finding under the reserved {@code (source, id)} below, so it rides the
 * coordinate's ledger key and goes with its other findings on eviction. It carries no severity and is never rendered as
 * a vulnerability. A re-scan that finds the coordinate still clean re-records it, sliding the freshness window; an
 * advisory row is checked before the marker, so a coordinate that turns vulnerable is served from its rows.
 */
public final class CleanScanMarker {

    private CleanScanMarker() {
    }

    /** The feed-neutral source the clean marker is attributed to - a scan result, not any one feed. */
    public static final String SOURCE = "scan";

    /** The reserved finding id the clean marker occupies on a coordinate. */
    public static final String ID = "clean";

    /** The default freshness window, past which the feeds are consulted again, so a newly disclosed advisory lands
     *  within a day. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /** The marker finding recorded when the feeds report nothing for a coordinate, seen now on both ends. */
    public static Finding of(Instant now) {
        return Finding.of(ID, SOURCE, Finding.Kind.CLEAN, "scan", Severity.NONE,
                "No advisories reported by the configured feeds.", now);
    }

    /** Whether a coordinate's rows carry an active clean-scan marker last seen within {@code ttl} of {@code now}. */
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
