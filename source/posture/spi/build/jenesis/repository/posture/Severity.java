package build.jenesis.repository.posture;

/**
 * How serious a {@link SecurityAdvisory} is, declared in ascending order so a report sorts critical-first with
 * {@link #compareTo}. There is no healthy severity: a condition that is fine raises no advisory.
 */
public enum Severity {

    /** A hardening hint - safe enough, but there is a stronger default. */
    INFO,
    /** A real hazard a public deployment should fix. */
    WARN,
    /** The instance is open, unauthenticated or otherwise exposed - act now. */
    CRITICAL;

    /** The more severe of this severity and {@code other}. */
    public Severity worst(Severity other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
