package build.jenesis.repository.findings;

import module java.base;

/**
 * The reachability-verdict label contract, shared by the reachability sweep and every surface that badges or filters
 * an advisory finding by it: a {@code (source="reachability", name="verdict")} label of {@link #REACHABLE} (a static
 * call path from some held artifact's code into the vulnerable symbols exists), {@link #NOT_REACHABLE} (no analyzed
 * consumer has one) or {@link #UNKNOWN} (the graph met a blind spot). The label narrows a view; it never removes a
 * finding, and a finding without it was not analyzed.
 */
public final class ReachabilityLabels {

    private ReachabilityLabels() {
    }

    /** The label source the reachability engine writes under. */
    public static final String SOURCE = "reachability";

    /** The label name carrying the aggregate verdict across every analyzed consumer. */
    public static final String NAME = "verdict";

    /** Some held artifact's own code statically reaches the vulnerable symbols. */
    public static final String REACHABLE = "reachable";

    /** No analyzed consumer reaches the vulnerable symbols, and the static graph met no blind spot. */
    public static final String NOT_REACHABLE = "not-reachable";

    /** The static graph cannot decide - reflection, unresolvable dispatch or missing bytecode stand in the way. */
    public static final String UNKNOWN = "unknown";

    /** Whether {@code value} is one of the three verdict spellings (case-insensitively). */
    public static boolean valid(String value) {
        return REACHABLE.equalsIgnoreCase(value) || NOT_REACHABLE.equalsIgnoreCase(value)
                || UNKNOWN.equalsIgnoreCase(value);
    }

    /** The verdict label on a finding, or empty when the engine has not analyzed it. */
    public static Optional<String> verdictOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name()) && valid(label.value())) {
                return Optional.of(label.value().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /** The stronger of two verdicts ({@code reachable} over {@code unknown} over {@code not-reachable}), for rows from
     *  several feeds merged onto one advisory; a {@code null} side yields the other. */
    public static String strongest(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return rank(left) >= rank(right) ? left : right;
    }

    /**
     * The verdicts of a coordinate's advisory findings, keyed by advisory id and every CVE alias (the identifiers the
     * vulnerability view de-duplicates by), each holding the strongest verdict among its rows.
     */
    public static Map<String, String> verdicts(List<Finding> findings) {
        Map<String, String> verdicts = new HashMap<>();
        for (Finding finding : findings) {
            Optional<String> verdict = verdictOf(finding);
            if (verdict.isEmpty()) {
                continue;
            }
            verdicts.merge(finding.id(), verdict.get(), ReachabilityLabels::strongest);
            for (String reference : finding.references()) {
                if (reference.startsWith("CVE-")) {
                    verdicts.merge(reference, verdict.get(), ReachabilityLabels::strongest);
                }
            }
        }
        return verdicts;
    }

    /**
     * Whether a row whose badge is {@code verdict} passes the view filter {@code filter}; blank matches everything. An
     * un-analyzed row matches {@code unknown}, so a triage view never hides what the engine has not reached.
     */
    public static boolean matches(String verdict, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (verdict == null || verdict.isEmpty()) {
            return UNKNOWN.equalsIgnoreCase(filter);
        }
        return verdict.equalsIgnoreCase(filter);
    }

    private static int rank(String verdict) {
        return switch (verdict.toLowerCase(Locale.ROOT)) {
            case REACHABLE -> 2;
            case UNKNOWN -> 1;
            default -> 0;
        };
    }
}
