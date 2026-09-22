package build.jenesis.repository.findings;

import module java.base;

/**
 * The reachability-verdict label contract shared by the writer (the {@code security/reachability} sweep) and every
 * surface that badges or filters a vulnerability finding by it - fixed here, beside {@link AdvisoryFindings}, so the
 * two sides never drift. The sweep attaches one label per advisory finding, {@code (source="reachability",
 * name="verdict")}, whose value is a three-valued category: {@link #REACHABLE} (a static call path from some held
 * artifact's own code into the vulnerable symbols exists), {@link #NOT_REACHABLE} (the static graph is confident no
 * such path exists from any analyzed consumer), or {@link #UNKNOWN} (the graph met a blind spot - reflection,
 * unresolvable dispatch, missing bytecode - and honestly refuses to assert either way). The label is a filterable
 * attribute, never a filter that removes anything: a finding without it simply was not analyzed, and every view
 * defaults to showing all findings regardless of verdict.
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

    /** The stronger of two verdicts - the conservative merge a view uses when advisory rows from several feeds
     *  collapse onto one displayed advisory: {@code reachable} outranks {@code unknown} outranks
     *  {@code not-reachable}, so a merged badge never under-reports. A {@code null} side yields the other. */
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
     * The verdicts of a coordinate's stored advisory findings, keyed by advisory id <em>and</em> every CVE alias the
     * finding carries - the same identifiers the vulnerability view de-duplicates rows by - each key holding the
     * strongest verdict among the rows it appeared on. A view renders the badge of a merged advisory by looking up
     * its id, falling back to its CVE aliases.
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
     * Whether a row whose badge is {@code verdict} (empty when not analyzed) passes the view filter {@code filter}
     * (blank matches everything). An un-analyzed row matches the {@code unknown} filter: absence of proof <em>is</em>
     * unknown reachability, so the conservative triage view ("everything not proven unreachable") never hides what
     * the engine has not reached yet. The filter is a view narrowing only - no store row is touched by it.
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
