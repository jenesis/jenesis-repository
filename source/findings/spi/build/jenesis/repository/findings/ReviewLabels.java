package build.jenesis.repository.findings;

import module java.base;

/**
 * The operator-review contract for AI-produced findings: an {@link Finding.Kind#AI_CANDIDATE} or
 * {@link Finding.Kind#APPLICABILITY} row is a candidate the operator confirms or dismisses, through a
 * {@code (source="operator", name="review")} label of {@link #CONFIRMED} or {@link #DISMISSED} with an optional
 * {@link #NOTE}. A dismissal is a label, not a deletion; the pending queue is the view of unreviewed rows, and a
 * decision can be flipped. {@link #apply} refuses a row that is not AI-produced, since a feed's row or a gate decision
 * is authoritative.
 */
public final class ReviewLabels {

    private ReviewLabels() {
    }

    /** The label source an operator's review decision writes under. */
    public static final String SOURCE = "operator";

    /** The label name carrying the review decision. */
    public static final String NAME = "review";

    /** The label name carrying the reviewer's optional note. */
    public static final String NOTE = "review-note";

    /** The operator confirmed the AI-produced finding as worth acting on. */
    public static final String CONFIRMED = "confirmed";

    /** The operator dismissed the AI-produced finding. */
    public static final String DISMISSED = "dismissed";

    /** The finding kinds a review decision applies to: the AI-produced ones. */
    public static final Set<Finding.Kind> REVIEWABLE = Set.of(Finding.Kind.AI_CANDIDATE, Finding.Kind.APPLICABILITY);

    /** The most characters of note one review keeps. */
    private static final int NOTE_CEILING = 1000;

    /** Whether {@code decision} is one of the two review spellings (case-insensitively). */
    public static boolean valid(String decision) {
        return CONFIRMED.equalsIgnoreCase(decision) || DISMISSED.equalsIgnoreCase(decision);
    }

    /** Whether a finding of this kind takes a review decision at all. */
    public static boolean reviewable(Finding.Kind kind) {
        return REVIEWABLE.contains(kind);
    }

    /** The review decision on a finding ({@code confirmed} / {@code dismissed}), or empty while it is pending. */
    public static Optional<String> reviewOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name()) && valid(label.value())) {
                return Optional.of(label.value().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /**
     * Applies a review decision to the AI-produced finding {@code (source, id)}: the decision label, and the note label
     * for a non-blank note. A repeated review refreshes its own labels.
     *
     * @throws IllegalArgumentException when the decision is unknown, no such finding exists, or it is not
     *                                  AI-produced
     */
    public static void apply(Findings ledger, String ecosystem, String coordinate, String version,
                             String source, String id, String decision, String note, Instant now)
            throws IOException {
        if (!valid(decision)) {
            throw new IllegalArgumentException("Unknown review decision: " + decision);
        }
        Finding reviewed = null;
        for (Finding finding : ledger.of(ecosystem, coordinate, version)) {
            if (finding.source().equals(source) && finding.id().equals(id)) {
                reviewed = finding;
                break;
            }
        }
        if (reviewed == null) {
            throw new IllegalArgumentException("No finding " + source + ":" + id + " on " + coordinate
                    + ":" + version);
        }
        if (!reviewable(reviewed.kind())) {
            throw new IllegalArgumentException("Only an AI-produced finding takes a review decision; "
                    + source + ":" + id + " is " + reviewed.kind().wire());
        }
        String value = decision.toLowerCase(Locale.ROOT);
        ledger.label(ecosystem, coordinate, version, source, id, new Finding.Label(SOURCE, NAME, value, 1.0, now));
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            ledger.label(ecosystem, coordinate, version, source, id, new Finding.Label(SOURCE, NOTE,
                    trimmed.length() > NOTE_CEILING ? trimmed.substring(0, NOTE_CEILING) : trimmed, 1.0, now));
        }
    }
}
