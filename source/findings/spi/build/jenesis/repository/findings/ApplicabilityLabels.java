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

    /** The label, its three spellings in order. */
    public static final OrderedLabel LABEL = new OrderedLabel(SOURCE, NAME, APPLIES, UNKNOWN, NOT_APPLICABLE);

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
