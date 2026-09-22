package build.jenesis.repository.findings;

import module java.base;

/**
 * The operator-review contract for AI-produced findings, fixed here so the review surfaces (the console queue, the
 * {@code /api/findings/review} endpoint) and every reader of a review mark never drift. An AI-produced row - a
 * {@link Finding.Kind#AI_CANDIDATE} the code-audit sweep emitted, or an {@link Finding.Kind#APPLICABILITY}
 * judgement - is always a <em>labeled candidate the operator confirms or dismisses</em>, never an authoritative
 * verdict; this contract is that confirmation channel: a {@code (source="operator", name="review")} label whose
 * value is {@link #CONFIRMED} or {@link #DISMISSED}, with the optional {@link #NOTE} sibling carrying the
 * reviewer's reasoning.
 *
 * <p>Categorize-never-discard holds for the review itself: a dismissal is a label, not a deletion or a
 * supersession - the row stays fully present and served with its mark, the pending queue is merely the <em>view</em>
 * of unreviewed rows, and a decision is reversible (the label channel's own-value refresh lets a reviewer flip a
 * mistaken dismissal back). {@link #apply} refuses any row that is not AI-produced: an advisory feed's row or a
 * gate decision is authoritative data no review label may editorialize - dismissing those is exactly the discard
 * semantics this ledger forbids.
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

    /** The operator dismissed the AI-produced finding - a mark on the still-present row, never a removal. */
    public static final String DISMISSED = "dismissed";

    /** The finding kinds a review decision may be applied to - exactly the AI-produced ones. */
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
     * Apply an operator's review decision to the AI-produced finding identified by {@code (source, id)} on a
     * coordinate: the decision label, and the note label when a non-blank note was given. A repeated review
     * refreshes its own labels (the sanctioned own-value refresh), so a decision is always reversible.
     *
     * @throws IllegalArgumentException when the decision spelling is unknown, no such finding exists on the
     *                                  coordinate, or the finding is not AI-produced (an authoritative row is
     *                                  never editorialized by a review label)
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
