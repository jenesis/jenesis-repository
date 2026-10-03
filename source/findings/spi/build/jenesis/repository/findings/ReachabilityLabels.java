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

    /** The label, its three spellings in order. */
    public static final OrderedLabel LABEL = new OrderedLabel(SOURCE, NAME, REACHABLE, UNKNOWN, NOT_REACHABLE);

    /** Whether {@code value} is one of the three spellings (case-insensitively). */
    public static boolean valid(String value) {
        return LABEL.valid(value);
    }

    /** The verdict a finding carries, or empty when it has none. */
    public static Optional<String> verdictOf(Finding finding) {
        return LABEL.verdictOf(finding);
    }

    /** The stronger of two verdicts ({@link OrderedLabel#strongest}). */
    public static String strongest(String left, String right) {
        return LABEL.strongest(left, right);
    }

    /** The verdicts of a coordinate's advisory findings ({@link OrderedLabel#verdicts}). */
    public static Map<String, String> verdicts(List<Finding> findings) {
        return LABEL.verdicts(findings);
    }

    /** Whether a row whose badge is {@code verdict} passes the view filter {@code filter}
     *  ({@link OrderedLabel#matches}). */
    public static boolean matches(String verdict, String filter) {
        return LABEL.matches(verdict, filter);
    }
}
