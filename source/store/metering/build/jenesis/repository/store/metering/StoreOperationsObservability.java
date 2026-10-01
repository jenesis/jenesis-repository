package build.jenesis.repository.store.metering;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.store.Retries;

/**
 * The store operations this node issued, on the observability report: a counter per operation under
 * {@code jenrepo.store.ops.<op>} and the class totals {@code jenrepo.store.ops.reads} and {@code .writes} - what the
 * operation-count suites read and the soak prints per interval, so a filling store shows constant operations per
 * request.
 *
 * <p>Beside them, what the compare-and-set loops made of refused writes, under {@code jenrepo.store.cas.*}: conditional
 * writes tried, refusals that had in fact landed ({@code replayed}), refusals finding a peer's bytes
 * ({@code lost.peer}), and refusals finding this node's bytes with work outstanding ({@code lost.unsettled}). Totals
 * alone cannot tell a lost write from a deferred counter flush; these verdicts can.
 */
public final class StoreOperationsObservability implements ObservabilitySource {

    @Override
    public List<Metric> metrics() {
        List<Metric> metrics = new ArrayList<>();
        long reads = 0;
        long writes = 0;
        for (Map.Entry<String, Long> entry : MeteringArtifactStore.operations().entrySet()) {
            metrics.add(Metric.counter("jenrepo.store.ops." + signal(entry.getKey()),
                    "Store operations named " + entry.getKey() + " this node has issued since it started.",
                    entry.getValue(), "operations"));
            if (MeteringArtifactStore.writes(entry.getKey())) {
                writes += entry.getValue();
            } else {
                reads += entry.getValue();
            }
        }
        metrics.add(Metric.counter("jenrepo.store.ops.reads", "Read-class store operations (GET, HEAD, listing) this node "
                + "has issued since it started.", reads, "operations"));
        metrics.add(Metric.counter("jenrepo.store.ops.writes", "Write-class store operations (PUT, DELETE) this node has "
                + "issued since it started - twelve reads' worth each on every object store.", writes, "operations"));
        metrics.add(Metric.counter("jenrepo.store.cas.tried", "Conditional writes the compare-and-set loops have asked "
                + "the store for since this node started, landed or refused - the denominator of the three verdicts.",
                Retries.tried(), "writes"));
        metrics.add(Metric.counter("jenrepo.store.cas.replayed", "Refused conditional writes that had in fact landed - "
                + "the key held what the try wrote and the mutation had nothing left to do. Climbs on an object store "
                + "whose SDK retried a write the service had accepted; zero on a filesystem.",
                Retries.replayed(), "writes"));
        metrics.add(Metric.counter("jenrepo.store.cas.lost.peer", "Refused conditional writes whose key turned out to "
                + "hold another writer's bytes, or nothing - an honest loss to a peer, retried against a fresh read. "
                + "Zero on a serial publish; anything else there is the store refusing a write it did not refuse for "
                + "a reason the loop can see.", Retries.lostToPeer(), "writes"));
        metrics.add(Metric.counter("jenrepo.store.cas.lost.unsettled", "Refused conditional writes whose key held this "
                + "node's bytes while the mutation still had work to do - an accumulation, a claim, a delete - and was "
                + "retried rather than believed landed.", Retries.lostUnsettled(), "writes"));
        // A different prefix from jenrepo.store.ops.*: the totals above partition that namespace by operation name, so
        // a family-suffixed name in it would be counted into the reads.
        MeteringArtifactStore.byFamily().forEach((name, count) -> {
            String[] split = name.split(" ", 2);
            metrics.add(Metric.counter("jenrepo.store.family." + signal(split[0]) + "." + segment(split[1]),
                    "Store operations named " + split[0] + " this node has issued against keys under "
                            + split[1] + ".", count, "operations"));
        });
        return metrics;
    }

    /** A key family as signal segments. It must be total: a signal segment is {@code [a-z][a-z0-9]*}, and this is the
     *  one name derived from a store key, so any key must yield a legal name. A family with a digit-leading segment -
     *  an audit trail keyed by date, {@code .system/audit/<n>-09-09} - would otherwise clean to
     *  {@code system.audit.n.09.09}, whose {@code 09} the grammar refuses; a refused {@link Metric} name throws and
     *  drops this whole source from the report. So every part is made to begin with a letter, and the cleaning is
     *  ASCII-only - {@code Character.isLetterOrDigit} would pass an accented letter the grammar also refuses. */
    static String segment(String family) {
        StringBuilder cleaned = new StringBuilder();
        for (char c : family.toLowerCase(Locale.ROOT).toCharArray()) {
            cleaned.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' ? c : '.');
        }
        List<String> parts = new ArrayList<>();
        for (String part : cleaned.toString().split("\\.+")) {
            if (part.isEmpty()) {
                continue;
            }
            // A digit-leading part is a number the layout carries - a date, a generation - which the grammar cannot
            // spell.
            parts.add(Character.isDigit(part.charAt(0)) ? "n" + part : part);
        }
        return parts.isEmpty() ? "none" : String.join(".", parts);
    }

    /** An operation's name as signal segments: {@code readVersioned} is {@code read.versioned}, because a segment is
     *  lower case and {@link Metric} refuses any other name, which would drop this source from the report. */
    static String signal(String op) {
        StringBuilder segments = new StringBuilder();
        for (char c : op.toCharArray()) {
            if (Character.isUpperCase(c)) {
                segments.append('.').append(Character.toLowerCase(c));
            } else {
                segments.append(c);
            }
        }
        return segments.toString();
    }
}
