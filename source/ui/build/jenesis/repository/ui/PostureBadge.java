package build.jenesis.repository.ui;

/**
 * The security-posture indicator the console header carries: how many unsafe-configuration advisories this view
 * raises, or that the report could not be collected at all.
 *
 * <p>The two are deliberately different states. A deployment with no advisories is clean and shows nothing; one
 * whose report could not be read is <em>unknown</em>, and saying "0" there would report a clean posture nobody
 * established. {@link #visible} is true for both an unknown report and a non-empty one, so the header falls silent
 * only when there is genuinely nothing to say.
 */
public record PostureBadge(boolean known, int count) {

    public PostureBadge {
        if (count < 0) {
            throw new IllegalArgumentException("An advisory count is never negative: " + count);
        }
    }

    /** A collected report carrying {@code count} advisories. */
    public static PostureBadge of(int count) {
        return new PostureBadge(true, count);
    }

    /** A report that could not be collected, which is not the same as one that found nothing. */
    public static PostureBadge unknown() {
        return new PostureBadge(false, 0);
    }

    public boolean visible() {
        return !known || count > 0;
    }

    public String label() {
        // No glyph here. The badge's kind draws its own mark in CSS, so a character typed into the label is a
        // second one beside it - and this label was carrying a warning sign onto a badge the shell renders as
        // danger, which would have put a cross and a triangle side by side saying the same thing differently.
        return known ? count + " posture" : "posture unknown";
    }

    public String title() {
        return known
                ? count + " security-posture advisory(ies) - unsafe configuration warnings this view raises, the "
                        + "same rows the Security-posture screen lists"
                : "The security-posture report could not be collected, so whether this deployment carries an unsafe "
                        + "setting is unknown rather than clear. Open the Security-posture screen for the reason.";
    }
}
