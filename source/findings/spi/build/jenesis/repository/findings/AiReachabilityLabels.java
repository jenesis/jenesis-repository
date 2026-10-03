package build.jenesis.repository.findings;

import module java.base;

/**
 * The AI reachability-opinion label contract, shared by its writer and every surface that badges or filters by it.
 * Where the deterministic call graph answered {@code unknown}, a classifier may offer a model-based second opinion as
 * a {@code (source="ai-reachability", name="verdict")} label beside the deterministic one, never replacing it; the
 * {@link #MODEL} label names the model that answered and {@link #RATIONALE} carries its reasoning.
 *
 * <p>{@link #agreed} ranks a decisive deterministic verdict above any opinion, so a stale opinion under a sharpened
 * static verdict is outranked. {@link #LIKELY_REACHABLE} and {@link #LIKELY_NOT_REACHABLE} are spelled as opinions,
 * and a low-confidence answer is {@link #UNKNOWN}.
 */
public final class AiReachabilityLabels {

    private AiReachabilityLabels() {
    }

    /** The label source the AI reachability classifier writes under. */
    public static final String SOURCE = "ai-reachability";

    /** The label name carrying the classifier's opinion. */
    public static final String NAME = "verdict";

    /** The label name carrying the model's reasoning about the blind-spot call sites it read. */
    public static final String RATIONALE = "rationale";

    /** The label name carrying the answering model's attribution ({@code LanguageModel.describe()}). */
    public static final String MODEL = "model";

    /** The model's opinion that a blind-spot site plausibly leads into the vulnerable code. */
    public static final String LIKELY_REACHABLE = "likely-reachable";

    /** The model's opinion that the flagged sites are unrelated to the vulnerable code. */
    public static final String LIKELY_NOT_REACHABLE = "likely-not-reachable";

    /** The model cannot tell, which a low-confidence answer also records. */
    public static final String UNKNOWN = "unknown";

    /**
     * The two engines' combined verdict. A decisive static verdict wins whatever the opinion says, so an opinion never
     * downgrades a deterministic {@code reachable}; where the static verdict is {@code unknown} or absent, the opinion
     * decides ({@code likely-reachable} as {@code reachable}, {@code likely-not-reachable} as {@code not-reachable}).
     */
    public static String agreed(String staticVerdict, String aiVerdict) {
        if (ReachabilityLabels.REACHABLE.equalsIgnoreCase(staticVerdict)) {
            return ReachabilityLabels.REACHABLE;
        }
        if (ReachabilityLabels.NOT_REACHABLE.equalsIgnoreCase(staticVerdict)) {
            return ReachabilityLabels.NOT_REACHABLE;
        }
        if (LIKELY_REACHABLE.equalsIgnoreCase(aiVerdict)) {
            return ReachabilityLabels.REACHABLE;
        }
        if (LIKELY_NOT_REACHABLE.equalsIgnoreCase(aiVerdict)) {
            return ReachabilityLabels.NOT_REACHABLE;
        }
        return ReachabilityLabels.UNKNOWN;
    }

    /**
     * Whether a row with these badges (either empty when absent) passes the view filter {@code filter}: a bare verdict
     * keys on the static label ({@link ReachabilityLabels#matches}), {@code ai:<opinion>} on the AI label (no opinion
     * matches {@code ai:unknown}), and {@code agreed:<verdict>} on the {@linkplain #agreed combined verdict}.
     */
    public static boolean matches(String staticVerdict, String aiVerdict, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String trimmed = filter.trim();
        if (trimmed.regionMatches(true, 0, "ai:", 0, 3)) {
            return LABEL.matches(aiVerdict, trimmed.substring(3));
        }
        if (trimmed.regionMatches(true, 0, "agreed:", 0, 7)) {
            return agreed(staticVerdict, aiVerdict).equalsIgnoreCase(trimmed.substring(7));
        }
        return ReachabilityLabels.matches(staticVerdict, trimmed);
    }

    /** The label, its three spellings in order. */
    public static final OrderedLabel LABEL = new OrderedLabel(SOURCE, NAME, LIKELY_REACHABLE, UNKNOWN,
            LIKELY_NOT_REACHABLE);

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
}
