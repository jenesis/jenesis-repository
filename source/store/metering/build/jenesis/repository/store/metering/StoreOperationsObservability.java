package build.jenesis.repository.store.metering;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.store.Retries;

/**
 * The store operations this node has issued, on the observability report: one counter per operation name under
 * {@code jenreg.store.ops.<op>} and the two class totals {@code jenreg.store.ops.reads} and {@code .writes}. What
 * the operation-count suites read - a download, a publish and a walked object each held to a standard of reads and
 * writes - and what the soak prints per interval, so a store that fills up shows constant operations per request.
 *
 * <p>Beside them, what the compare-and-set loops made of the writes the store refused, under
 * {@code jenreg.store.cas.*}: how many conditional writes were tried, how many refusals turned out to have landed
 * ({@code replayed}), how many found a peer's bytes on the key ({@code lost.peer}) and how many found this node's
 * bytes but work still outstanding ({@code lost.unsettled}). {@link Retries} counted the verdicts for months and
 * nothing read them, which left the object-store canary arguing about lost writes from read and write totals
 * alone - and the totals turned out to be a deferred counter flush, not a refused write. A canary prints these
 * per window now, so "the store refused a write it had accepted" is a number on a serial publish rather than a
 * theory about one.
 */
public final class StoreOperationsObservability implements ObservabilitySource {

    @Override
    public List<Metric> metrics() {
        List<Metric> metrics = new ArrayList<>();
        long reads = 0;
        long writes = 0;
        for (Map.Entry<String, Long> entry : MeteringArtifactStore.operations().entrySet()) {
            metrics.add(Metric.counter("jenreg.store.ops." + signal(entry.getKey()),
                    "Store operations named " + entry.getKey() + " this node has issued since it started.",
                    entry.getValue(), "operations"));
            if (MeteringArtifactStore.writes(entry.getKey())) {
                writes += entry.getValue();
            } else {
                reads += entry.getValue();
            }
        }
        metrics.add(Metric.counter("jenreg.store.ops.reads", "Read-class store operations (GET, HEAD, listing) this node "
                + "has issued since it started.", reads, "operations"));
        metrics.add(Metric.counter("jenreg.store.ops.writes", "Write-class store operations (PUT, DELETE) this node has "
                + "issued since it started - twelve reads' worth each on every object store.", writes, "operations"));
        metrics.add(Metric.counter("jenreg.store.cas.tried", "Conditional writes the compare-and-set loops have asked "
                + "the store for since this node started, landed or refused - the denominator of the three verdicts.",
                Retries.tried(), "writes"));
        metrics.add(Metric.counter("jenreg.store.cas.replayed", "Refused conditional writes that had in fact landed - "
                + "the key held what the try wrote and the mutation had nothing left to do. Climbs on an object store "
                + "whose SDK retried a write the service had accepted; zero on a filesystem.",
                Retries.replayed(), "writes"));
        metrics.add(Metric.counter("jenreg.store.cas.lost.peer", "Refused conditional writes whose key turned out to "
                + "hold another writer's bytes, or nothing - an honest loss to a peer, retried against a fresh read. "
                + "Zero on a serial publish; anything else there is the store refusing a write it did not refuse for "
                + "a reason the loop can see.", Retries.lostToPeer(), "writes"));
        metrics.add(Metric.counter("jenreg.store.cas.lost.unsettled", "Refused conditional writes whose key held this "
                + "node's bytes while the mutation still had work to do - an accumulation, a claim, a delete - and was "
                + "retried rather than believed landed.", Retries.lostUnsettled(), "writes"));
        // Deliberately a different prefix, not more jenreg.store.ops.* names: the two totals above are summed by
        // partitioning that namespace on the operation name, so a family-suffixed name in it would be read as an
        // operation nobody recognises and counted into the reads.
        MeteringArtifactStore.byFamily().forEach((name, count) -> {
            String[] split = name.split(" ", 2);
            metrics.add(Metric.counter("jenreg.store.family." + signal(split[0]) + "." + segment(split[1]),
                    "Store operations named " + split[0] + " this node has issued against keys under "
                            + split[1] + ".", count, "operations"));
        });
        return metrics;
    }

    /**
     * A key family as signal segments: the separators a layout uses are not the separators a signal name may carry.
     *
     * <p><b>It has to be total, and the first cut was not.</b> A signal segment is {@code [a-z][a-z0-9]*} - it must
     * begin with a letter - and this is the one place in the report where a name is derived from a store key rather
     * than written by an author, so whatever a key contains has to come out the other side as a legal name. The
     * first cut cleaned non-alphanumerics to dots and stopped, which is legal only while no key family carries a
     * segment that starts with a digit. An audit trail keyed by date does: {@code .system/audit/2026-09-09} folds to
     * {@code .system/audit/<n>-09-09} and cleaned to {@code system.audit.n.09.09}, whose {@code 09} the grammar
     * refuses - and a refused {@link Metric} name is not one missing metric, it throws, and the report drops this
     * whole source as unavailable. Measured 2026-09-09: switching {@code jenreg.store-families} on made a node
     * report NO {@code jenreg.store.ops.*} counters at all, which read as "this node's store is not metered".
     *
     * <p>So every part is made to begin with a letter, and the cleaning is ASCII-only - {@code Character.isLetterOrDigit}
     * is Unicode-aware and would pass an accented letter straight into a name the grammar also refuses.
     */
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
            // A part that starts with a digit is a number the layout carries - a date, a generation, a version -
            // and the grammar has no spelling for one. Naming it as a number keeps it readable and legal.
            parts.add(Character.isDigit(part.charAt(0)) ? "n" + part : part);
        }
        return parts.isEmpty() ? "none" : String.join(".", parts);
    }

    /** An operation's name as signal segments: {@code readVersioned} is {@code read.versioned}, because a signal
     *  segment is lower-case and a name that is not one is refused by {@link Metric} - which dropped this whole
     *  source from the report as unavailable, silently, until a booted node was asked for its counters. */
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
