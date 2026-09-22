package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.compliance.Severity;

/**
 * A section's gate-and-GUI-facing summary, exposed on the envelope so a consumer can rank or chip the section
 * <em>without understanding its {@code data}</em>: a {@link Severity} band, or none (neutral). This realises the
 * observation - "each entry adds to a score or is neutral" - at the envelope, so an unknown section
 * (a custom module's, a newer writer's) still contributes severity to the verdict and renders a severity chip
 * through the generic renderer. A {@code null} severity is neutral: inspected, contributes nothing to the score.
 */
public record Signal(Severity severity) {

    /** The neutral signal - a section that contributes nothing to the gate's score. */
    public static final Signal NEUTRAL = new Signal(null);

    /** The signal for a severity band, or {@link #NEUTRAL} when {@code severity} is {@code null}. */
    public static Signal of(Severity severity) {
        return severity == null ? NEUTRAL : new Signal(severity);
    }

    /** Whether this signal contributes nothing to the gate's score (no severity band). */
    public boolean neutral() {
        return severity == null;
    }
}
