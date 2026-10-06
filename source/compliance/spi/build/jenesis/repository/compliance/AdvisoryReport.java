package build.jenesis.repository.compliance;

import module java.base;

/**
 * Assembles a vulnerability report from per-coordinate advisory findings and the installed {@link AdvisorySignal}
 * columns - the one place the report's shape and ordering live, shared by the server API and the console so the two
 * cannot drift. Each signal is evaluated once over the whole batch (so a network signal queries once), the rows are
 * rebuilt per coordinate, and the lines are ordered <em>with dependents first</em> - a coordinate a published
 * version is built against, as its resolved dependents say, above one only scored in the abstract - then by each
 * signal's strongest rank in signal order (the most urgent first), then by coordinate. Having dependents is the
 * primary key so what builds actually use always sorts above a merely scored coordinate; each line carries how many
 * dependents it has, which a surface shows as "Used by". Whether vulnerable code is reachable from a dependent is a
 * call-graph verdict, a badge of its own, and not what this orders by.
 */
public final class AdvisoryReport {

    private AdvisoryReport() {
    }

    /** One vulnerable coordinate, how many published versions are built against it - {@code 0} where none is known -
     *  and its advisory rows. */
    public record Line(String coordinate, int usedBy, List<Row> advisories) {
    }

    /** One advisory: its fixed facts (id, severity, malicious, fixed version) and one cell per installed signal. */
    public record Row(String id, Severity severity, boolean malicious, String fixed, List<Cell> signals) {
    }

    /** One signal's cell in a row: the column's name and label with the evaluated display text and sortable rank. */
    public record Cell(String name, String label, String value, double rank) {
    }

    /** Assemble the report lines with no dependents known - every coordinate is treated as merely scored, so the
     *  ordering falls back to the signal ranks then coordinate, for a caller with no dependents to read. */
    public static List<Line> assemble(List<AdvisorySignal> signals,
                                      SequencedMap<String, List<AdvisorySource.Advisory>> findings) {
        return assemble(signals, findings, Map.of());
    }

    /** Assemble the report lines from each coordinate's advisories (in finding order), evaluating every signal once
     *  over the flattened batch. A line whose coordinate {@code usedBy} counts dependents for sorts above every
     *  merely-scored line, so a reviewer meets the advisories builds actually use first. */
    public static List<Line> assemble(List<AdvisorySignal> signals,
                                      SequencedMap<String, List<AdvisorySource.Advisory>> findings,
                                      Map<String, Integer> usedBy) {
        List<AdvisorySource.Advisory> flattened = new ArrayList<>();
        for (List<AdvisorySource.Advisory> advisories : findings.values()) {
            flattened.addAll(advisories);
        }
        List<List<AdvisorySignal.Value>> columns = new ArrayList<>();
        // Each signal's identity is read ONCE, here, before it is evaluated, and every later use - the shape refusal
        // below and every cell of its column - reads that capture. The refusal names a signal that answered the wrong
        // number of values, and asking that very signal its name there would let one broken enough to mis-answer and
        // then throw from name() defeat the diagnostic, escaping into the handler that renders /api/vulnerabilities
        // and the console's compliance review. And read per cell, name() and label() would re-enter each signal once
        // per advisory to ask it two constants.
        List<String> names = new ArrayList<>(signals.size());
        List<String> labels = new ArrayList<>(signals.size());
        for (AdvisorySignal signal : signals) {
            names.add(signal.name());
            labels.add(signal.label());
        }
        for (int column = 0; column < signals.size(); column++) {
            List<AdvisorySignal.Value> values = signals.get(column).evaluate(flattened);
            if (values.size() != flattened.size()) {
                throw new IllegalStateException("Signal '" + names.get(column) + "' answered " + values.size()
                        + " values for " + flattened.size() + " advisories.");
            }
            columns.add(values);
        }
        List<Line> lines = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, List<AdvisorySource.Advisory>> finding : findings.entrySet()) {
            List<Row> rows = new ArrayList<>();
            for (AdvisorySource.Advisory advisory : finding.getValue()) {
                List<Cell> cells = new ArrayList<>();
                for (int column = 0; column < signals.size(); column++) {
                    AdvisorySignal.Value value = columns.get(column).get(index);
                    cells.add(new Cell(names.get(column), labels.get(column), value.display(), value.rank()));
                }
                rows.add(new Row(advisory.id(), advisory.severity(), advisory.malicious(), advisory.fixed(), cells));
                index++;
            }
            lines.add(new Line(finding.getKey(), Math.max(0, usedBy.getOrDefault(finding.getKey(), 0)), rows));
        }
        // Decorate each line with whether it has dependents and its per-signal strongest rank once, so the sort compares
        // precomputed keys rather than re-streaming every line's advisories for a fresh max() on each of the O(n log n)
        // pairwise comparisons (the strongest rank per column is fixed the moment the rows are built).
        int signalCount = signals.size();
        record Ranked(Line line, boolean depended, double[] ranks) {
        }
        List<Ranked> ranked = new ArrayList<>(lines.size());
        for (Line line : lines) {
            double[] ranks = new double[signalCount];
            for (Row row : line.advisories()) {
                for (int column = 0; column < signalCount; column++) {
                    ranks[column] = Math.max(ranks[column], row.signals().get(column).rank());
                }
            }
            ranked.add(new Ranked(line, line.usedBy() > 0, ranks));
        }
        Comparator<Ranked> ordering = Comparator.comparing(Ranked::depended).reversed();  // with dependents first
        for (int column = 0; column < signalCount; column++) {
            int position = column;
            ordering = ordering.thenComparing(
                    Comparator.comparingDouble((Ranked line) -> line.ranks()[position]).reversed());
        }
        ranked.sort(ordering.thenComparing(Comparator.comparing(line -> line.line().coordinate())));
        List<Line> ordered = new ArrayList<>(ranked.size());
        for (Ranked line : ranked) {
            ordered.add(line.line());
        }
        return ordered;
    }
}
