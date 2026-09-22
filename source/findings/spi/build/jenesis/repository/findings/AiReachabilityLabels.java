package build.jenesis.repository.findings;

import module java.base;

/**
 * The AI reachability-opinion label contract, fixed here beside {@link ReachabilityLabels} so the writer (the
 * {@code ai/reachability} classifier sweep) and every surface that badges or filters by it never drift. Where the
 * deterministic call graph gave up - the {@code unknown} verdicts whose blind spots are reflection, unresolvable
 * dispatch, {@code ServiceLoader}, missing bytecode - the classifier reads the blind-spot call sites and offers a
 * <em>second, model-based opinion</em> as a {@code (source="ai-reachability", name="verdict")} label: an
 * <strong>addition beside the deterministic label, never a replacement of it</strong>. Both labels render together
 * ("static: unknown, AI: likely-reachable") and each names its provenance - the sibling {@link #MODEL} label says
 * exactly which model answered and where it runs, and {@link #RATIONALE} carries the model's reasoning about the
 * flagged sites.
 *
 * <p>The honesty guards are structural. The classifier only ever opines where the static verdict is {@code unknown},
 * so the two labels never compete on a decided case - and {@link #agreed} enforces the ranking anyway: a decisive
 * deterministic verdict <em>is</em> the combined answer whatever the AI once said (a stale opinion under a
 * since-sharpened static verdict is outranked, never trusted), and only where the graph honestly cannot tell does
 * the AI's advisory opinion fill in. The spellings say what they are: {@link #LIKELY_REACHABLE} and
 * {@link #LIKELY_NOT_REACHABLE} are opinions, never the proof the deterministic engine's unprefixed verdicts assert,
 * and a low-confidence answer stays {@link #UNKNOWN} rather than pretending to know.
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

    /** The model cannot tell either - the honest residue, also what a low-confidence answer records. */
    public static final String UNKNOWN = "unknown";

    /** Whether {@code value} is one of the three opinion spellings (case-insensitively). */
    public static boolean valid(String value) {
        return LIKELY_REACHABLE.equalsIgnoreCase(value) || LIKELY_NOT_REACHABLE.equalsIgnoreCase(value)
                || UNKNOWN.equalsIgnoreCase(value);
    }

    /** The AI opinion label on a finding, or empty when the classifier has not opined on it. */
    public static Optional<String> verdictOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name()) && valid(label.value())) {
                return Optional.of(label.value().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /** The stronger of two opinions - the conservative merge across a dependency's analyzed consumers:
     *  {@code likely-reachable} outranks {@code unknown} outranks {@code likely-not-reachable}, so an aggregate
     *  never under-reports. A {@code null} side yields the other. */
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
     * The AI opinions of a coordinate's stored advisory findings, keyed by advisory id and every CVE alias -
     * the same identifiers the vulnerability view de-duplicates rows by, mirroring
     * {@link ReachabilityLabels#verdicts} - each key holding the strongest opinion among the rows it appeared on.
     */
    public static Map<String, String> verdicts(List<Finding> findings) {
        Map<String, String> verdicts = new HashMap<>();
        for (Finding finding : findings) {
            Optional<String> verdict = verdictOf(finding);
            if (verdict.isEmpty()) {
                continue;
            }
            verdicts.merge(finding.id(), verdict.get(), AiReachabilityLabels::strongest);
            for (String reference : finding.references()) {
                if (reference.startsWith("CVE-")) {
                    verdicts.merge(reference, verdict.get(), AiReachabilityLabels::strongest);
                }
            }
        }
        return verdicts;
    }

    /**
     * The two engines' combined verdict - what the deterministic label and the AI label agree the row's category
     * is. The deterministic engine outranks: a decisive static verdict ({@code reachable} / {@code not-reachable})
     * <em>is</em> the agreement whatever the AI opinion says (the classifier only ever opines on {@code unknown}
     * verdicts, so a contradicting opinion can only be a stale one a since-sharpened analysis outran - it is
     * outranked, never trusted, and in particular an AI opinion can never downgrade a deterministic
     * {@code reachable}). Only where the static verdict is {@code unknown} or absent does the AI opinion decide
     * ({@code likely-reachable} counts as {@code reachable}, {@code likely-not-reachable} as
     * {@code not-reachable}); {@code unknown} remains exactly the rows neither engine can tell.
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
     * Whether a row whose deterministic badge is {@code staticVerdict} and AI badge is {@code aiVerdict} (either
     * empty when not analyzed / not opined) passes the view filter {@code filter} - the operator's choice of what
     * the facet keys on, and always a view narrowing only, never a store change:
     * <ul>
     * <li>a bare verdict ({@code reachable} / {@code not-reachable} / {@code unknown}) keys on the static label
     *     exactly as {@link ReachabilityLabels#matches} always did (an un-analyzed row matches {@code unknown});</li>
     * <li>{@code ai:likely-reachable} / {@code ai:likely-not-reachable} / {@code ai:unknown} keys on the AI label
     *     (a row without an opinion matches {@code ai:unknown} - absence of an opinion is unknown, the same
     *     conservative rule the static facet holds to);</li>
     * <li>{@code agreed:reachable} / {@code agreed:not-reachable} / {@code agreed:unknown} keys on the two labels'
     *     {@linkplain #agreed combined verdict} - {@code agreed:unknown} is exactly the residue neither engine can
     *     decide.</li>
     * </ul>
     */
    public static boolean matches(String staticVerdict, String aiVerdict, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String trimmed = filter.trim();
        if (trimmed.regionMatches(true, 0, "ai:", 0, 3)) {
            String opinion = aiVerdict == null || aiVerdict.isEmpty() ? UNKNOWN : aiVerdict;
            return opinion.equalsIgnoreCase(trimmed.substring(3));
        }
        if (trimmed.regionMatches(true, 0, "agreed:", 0, 7)) {
            return agreed(staticVerdict, aiVerdict).equalsIgnoreCase(trimmed.substring(7));
        }
        return ReachabilityLabels.matches(staticVerdict, trimmed);
    }

    private static int rank(String verdict) {
        return switch (verdict.toLowerCase(Locale.ROOT)) {
            case LIKELY_REACHABLE -> 2;
            case UNKNOWN -> 1;
            default -> 0;
        };
    }
}
