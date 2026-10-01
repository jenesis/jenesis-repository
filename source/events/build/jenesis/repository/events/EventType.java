package build.jenesis.repository.events;

/**
 * The kinds of repository event a producer emits and an {@link EventSink} may deliver: an accepted
 * {@link #PUBLISH publish}, a gate {@link #QUARANTINE quarantine} (withheld or rejected), a recorded
 * {@link #FINDING finding}, a staging {@link #PROMOTION promotion}, a hold's resolution by {@link #RELEASE release} or
 * {@link #DISCARD discard}, so a quarantine subscriber learns the hold resolved, and an {@link #UNPUBLISH unpublish},
 * the delete counterpart an edge cache purges on. The name is the wire token a subscriber filters on ({@link #wire}),
 * so a new kind is a new constant.
 */
public enum EventType {

    PUBLISH,
    QUARANTINE,
    FINDING,
    PROMOTION,
    RELEASE,
    DISCARD,
    UNPUBLISH;

    /** The lower-case token this type travels as on the wire and in an endpoint's event filter. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
