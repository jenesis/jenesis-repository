package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.compliance.Severity;

/**
 * A section's summary for the gate and the console, readable without understanding its {@code data}: a {@link Severity}
 * band, or neutral. So an unknown section (a custom module's, a newer writer's) still contributes to the verdict and
 * renders a severity chip. A {@code null} severity is neutral.
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
