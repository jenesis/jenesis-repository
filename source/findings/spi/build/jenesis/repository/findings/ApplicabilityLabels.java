package build.jenesis.repository.findings;

import module java.base;

/**
 * The AI applicability-label contract, shared by its writer and every surface that badges or filters by it. An
 * applicability sweep reads the context recorded against a coordinate and opines whether a finding applies there at
 * all (a CVE in a code path the library does not ship, a Windows-only issue on a Linux-only estate), as a
 * {@code (source="ai-applicability", name="applicability")} label beside the finding; {@link #MODEL} names the model
 * and {@link #RATIONALE} carries its reasoning.
 *
 * <p>The opinions are for review only: no surface drops or downgrades a finding for them and the gate never reads them.
 * A low-confidence answer is {@link #UNKNOWN}, and an un-judged finding matches the {@code unknown} facet.
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

    /** The model's opinion that the finding does not apply in this artifact's context. */
    public static final String NOT_APPLICABLE = "not-applicable";

    /** The model cannot tell, which a low-confidence answer also records. */
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
     * The applicability opinions of a coordinate's advisory findings, keyed as {@link ReachabilityLabels#verdicts} keys
     * them, each holding the {@linkplain #strongest most conservative} opinion among its rows.
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

    /** The more conservative of two opinions ({@code applies} over {@code unknown} over {@code not-applicable}); a
     *  {@code null} side yields the other. */
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
     * Whether a row whose badge is {@code verdict} (empty when never judged, which matches {@code unknown}) passes the
     * view filter {@code filter}; blank matches everything.
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
