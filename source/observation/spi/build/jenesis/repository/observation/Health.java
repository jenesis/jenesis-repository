package build.jenesis.repository.observation;

/**
 * The health of a check or an aggregate, in ascending severity so {@link #worst} collapses many into one: {@link #UP}
 * is quiet, {@link #UNKNOWN} (a source could not determine its state) is surfaced but not paged, {@link #DEGRADED}
 * warns, and one {@link #DOWN} is worth paging on. No checks at all aggregate to {@link #UP}.
 */
public enum Health {

    /** Healthy. */
    UP,
    /** A source could not determine its state. */
    UNKNOWN,
    /** Working, but with a warning worth surfacing. */
    DEGRADED,
    /** Broken - the state worth paging on. */
    DOWN;

    /** The more severe of this state and {@code other}. */
    public Health worst(Health other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
