package build.jenesis.repository.ui;

/**
 * The security-posture indicator in the console header: how many advisories this view raises, or that the report
 * could not be collected, which is shown rather than read as a clean zero. {@link #visible} is false only for a
 * collected report with no advisories.
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
        // No glyph: the badge's kind draws its own mark in CSS.
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
