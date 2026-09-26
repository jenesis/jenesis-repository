package build.jenesis.repository.events;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The publish and unpublish producers of the event seam: the store's after-commit hook, discovered like any
 * {@link PublicationObserver}, turned into an {@link EventSink#emit} fan-out. Because the store contract fires
 * {@link #onPublished} only for an accepted (linked, serving) publish, a quarantined or rejected artifact never raises
 * a publish event - the quarantine leg is the gate's own event - and because it fires {@link #onDeleted} once per
 * removed pointer, a reviewer discard, a retention sweep and a retro-screening withhold all raise the unpublish
 * counterpart without this module knowing which of them happened. A format's own deploy, a staging promotion and a
 * proxied caching all feed it without the event seam touching a format.
 *
 * <p><b>Why this lives in the SPI's home module rather than in a delivery module.</b> It used to be
 * {@code WebhookPublicationObserver}, provided by {@code webhook}, and it wrote its note straight into
 * {@code WebhookOutbox} - so {@code PUBLISH} and {@code UNPUBLISH} never travelled {@link EventSink#emit} at all, and
 * a second sink added to a deployment would silently have received five of the seven {@link EventType} constants while
 * the seam's own preamble claimed every installed sink observes the event. A producer belongs beside the seam it
 * produces into, not inside one of its consumers: here, every installed sink sees a publish, and a deployment that
 * swaps its delivery module keeps its publish notifications. The events module is required by every producer module,
 * so the producer is present exactly when the seam is.
 *
 * <p>An artifact with no coordinate - a checksum, a signature, a generated sidecar - raises no event, deliberately, so
 * a publish is announced once for the artifact rather than once per {@code .sha256} beside it. That gate lives here
 * rather than in a sink because it is a statement about what an event <em>is</em>: {@link RepositoryEvent} names a
 * coordinate, and a subscriber filtering on one cannot use an event that carries none. It is also what keeps the
 * common case free - the fan-out is not even resolved for a sidecar.
 *
 * <p>Enablement is not read here either. Whether a notification is wanted is each sink's own dial (the webhook sink
 * records nothing while {@code webhook} is off), so a producer that consulted one delivery module's latch would
 * silently decide for every other sink too.
 */
public final class EventPublicationObserver implements PublicationObserver {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        if (artifact.coordinate() == null) {
            return;                                             // a checksum or generated sidecar: not its own event
        }
        EventSink.emit(store, RepositoryEvent.publish(
                artifact.ecosystem(), artifact.coordinate(), artifact.version(), artifact.path(), Instant.now()));
    }

    /**
     * The delete counterpart of {@link #onPublished}, so a subscriber that reacted to the publish is told the
     * coordinate stopped serving. It is the CDN-origin purge trigger: an edge cache that cached the
     * artifact under its own URL subscribes to {@code unpublish} and purges it, so a serve plane that just stopped
     * honouring a redirect target does not keep serving it from the edge.
     */
    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
        if (artifact.coordinate() == null) {
            return;
        }
        EventSink.emit(store, RepositoryEvent.unpublish(
                artifact.ecosystem(), artifact.coordinate(), artifact.version(), artifact.path(), Instant.now()));
    }
}
