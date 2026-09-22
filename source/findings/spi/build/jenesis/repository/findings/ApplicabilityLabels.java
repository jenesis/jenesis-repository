package build.jenesis.repository.findings;

import module java.base;

/**
 * The AI applicability-label contract, fixed here beside {@link AiReachabilityLabels} so the writer (the
 * {@code ai/analysis} applicability sweep) and every surface that badges or filters by it never drift. The sweep
 * reads the textual context the other plugins already recorded against a coordinate - the advisory's own
 * description, the license and gate rows, the reachability verdicts and their labels - and offers an opinion on
 * whether a reported finding <em>applies in this artifact's context at all</em> (a CVE in a code path the library
 * does not ship, a Windows-only issue on a Linux-only estate), written as a {@code (source="ai-applicability",
 * name="applicability")} label on the advisory finding: an <strong>addition beside the finding, never a gate on
 * it</strong>. The finding stays fully present and served; the label only helps the operator filter the view.
 * The sibling {@link #MODEL} label names exactly which model answered, and {@link #RATIONALE} carries its
 * reasoning, so the provenance of the opinion is always explicit.
 *
 * <p>The honesty guards are structural. {@link #APPLIES} and {@link #NOT_APPLICABLE} are opinions for review,
 * never verdicts that hide anything: no surface drops or downgrades a finding because of them, the gate never
 * reads them, and a low-confidence answer stays {@link #UNKNOWN} rather than pretending to know. An un-judged
 * finding matches the {@code unknown} facet - absence of an opinion is unknown applicability, never a clean bill.
 */
public final class ApplicabilityLabels {

    private ApplicabilityLabels() {
    }

    /** The label source the AI applicability sweep writes under. */
    public static final String SOURCE = "ai-applicability";

    /** The label name carrying the applicability opinion. */
    public static final String NAME = "applicability";

    /** The label name carrying the model's reasoning about the context it read. */
    public static final String RATIONALE = "rationale";

    /** The label name carrying the answering model's attribution ({@code LanguageModel.describe()}). */
    public static final String MODEL = "model";

    /** The model's opinion that the finding applies to this artifact as held here. */
    public static final String APPLIES = "applies";

    /** The model's opinion that the finding does not apply in this artifact's context - an opinion for the
     *  operator's filter, never a removal or a gate downgrade. */
    public static final String NOT_APPLICABLE = "not-applicable";

    /** The model cannot tell - the honest residue, also what a low-confidence answer records. */
    public static final String UNKNOWN = "unknown";

    /** Whether {@code value} is one of the three opinion spellings (case-insensitively). */
    public static boolean valid(String value) {
        return APPLIES.equalsIgnoreCase(value) || NOT_APPLICABLE.equalsIgnoreCase(value)
                || UNKNOWN.equalsIgnoreCase(value);
    }

    /** The applicability opinion label on a finding, or empty when the sweep has not judged it. */
    public static Optional<String> verdictOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name()) && valid(label.value())) {
                return Optional.of(label.value().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /**
     * The applicability opinions of a coordinate's stored advisory findings, keyed by advisory id and every CVE
     * alias - the same identifiers the vulnerability view de-duplicates rows by, mirroring
     * {@link AiReachabilityLabels#verdicts} - each key holding the most conservative opinion among the rows it
     * appeared on ({@code applies} outranks {@code unknown} outranks {@code not-applicable}, so an aggregate
     * never under-reports).
     */
    public static Map<String, String> verdicts(List<Finding> findings) {
        Map<String, String> verdicts = new HashMap<>();
        for (Finding finding : findings) {
            Optional<String> verdict = verdictOf(finding);
            if (verdict.isEmpty()) {
                continue;
            }
            verdicts.merge(finding.id(), verdict.get(), ApplicabilityLabels::strongest);
            for (String reference : finding.references()) {
                if (reference.startsWith("CVE-")) {
                    verdicts.merge(reference, verdict.get(), ApplicabilityLabels::strongest);
                }
            }
        }
        return verdicts;
    }

    /** The more conservative of two opinions: {@code applies} outranks {@code unknown} outranks
     *  {@code not-applicable}, so a merged badge never under-reports. A {@code null} side yields the other. */
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
     * Whether a row whose applicability badge is {@code verdict} (empty when never judged) passes the view filter
     * {@code filter} ({@code applies} / {@code not-applicable} / {@code unknown}; blank matches everything) - a
     * facet over the view only, never a store change. A never-judged row matches {@code unknown}: absence of an
     * opinion is unknown applicability, the same conservative rule the reachability facet holds to.
     */
    public static boolean matches(String verdict, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String opinion = verdict == null || verdict.isEmpty() ? UNKNOWN : verdict;
        return opinion.equalsIgnoreCase(filter.trim());
    }

    private static int rank(String verdict) {
        return switch (verdict.toLowerCase(Locale.ROOT)) {
            case APPLIES -> 2;
            case UNKNOWN -> 1;
            default -> 0;
        };
    }
}
