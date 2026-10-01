package build.jenesis.repository.events;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * The publish and unpublish producers of the event seam: the store's after-commit hook, discovered as a
 * {@link PublicationObserver}, turned into an {@link EventSink#emit} fan-out. {@link #onPublished} fires only for an
 * accepted, serving publish, so a quarantined or rejected artifact raises no publish event; {@link #onDeleted} fires
 * once per removed pointer, so a discard, a retention sweep and a retro-screening withhold all raise an unpublish.
 *
 * <p>It lives in the seam's home rather than in a delivery module, so every installed sink sees a publish; one written
 * into a delivery module's own outbox would leave every other sink blind to it. Every producer module requires this
 * one, so the producer is present exactly when the seam is.
 *
 * <p>An artifact with no coordinate - a checksum, a signature, a sidecar - raises no event, so a publish is announced
 * once per artifact; a {@link RepositoryEvent} names a coordinate, and the fan-out is not even resolved for a sidecar.
 * Enablement is each sink's own dial and is not read here, or one delivery module's latch would decide for every sink.
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

    /** The delete counterpart of {@link #onPublished}, so a subscriber is told the coordinate stopped serving: the
     *  trigger a CDN-origin edge cache purges on. */
    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
        if (artifact.coordinate() == null) {
            return;
        }
        EventSink.emit(store, RepositoryEvent.unpublish(
                artifact.ecosystem(), artifact.coordinate(), artifact.version(), artifact.path(), Instant.now()));
    }
}
