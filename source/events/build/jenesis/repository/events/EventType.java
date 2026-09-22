package build.jenesis.repository.events;

/**
 * The kinds of repository event a producer emits and an {@link EventSink} may deliver: an accepted {@link #PUBLISH
 * publish} (an artifact linked and now serving), a gate {@link #QUARANTINE quarantine} (an upload or proxied
 * artifact withheld or rejected), a newly recorded {@link #FINDING finding} (a vulnerability, licence or quality
 * row appended against a coordinate), a staging {@link #PROMOTION promotion} (a staged set released in full), and
 * the resolution of a hold - a reviewer {@link #RELEASE release} (a withheld artifact promoted back into the layout)
 * or {@link #DISCARD discard} (a withheld artifact destroyed) - so an integrator that reacted to the {@link
 * #QUARANTINE} webhook is told the hold resolved rather than left watching a queue that never clears - and an
 * {@link #UNPUBLISH unpublish} (a previously-serving coordinate removed), the delete counterpart of {@link #PUBLISH}
 * that a CDN-origin edge cache subscribes to so a withdrawn or retro-screened artifact is purged from the edge.
 * The name is the type token that travels on the wire ({@link #wire}) and the value a subscriber filters on, so a
 * new event kind is a new constant here, never a free-text string a producer and a filter must keep in lock-step.
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
