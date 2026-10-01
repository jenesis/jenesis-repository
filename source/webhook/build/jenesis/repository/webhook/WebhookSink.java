package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The discovered {@link EventSink} turning a repository event into a queued webhook: it only writes a note in the
 * event's scoped {@link WebhookOutbox}, so emission never blocks the producer; the HTTP delivery is the
 * {@link WebhookDeliveryTask}'s. Every {@code EventType} arrives here through {@link EventSink#emit}. It records
 * nothing while the feature is off ({@link Webhooks#enabled()}); with it on and no endpoint configured, the next drain
 * drops the note.
 */
public final class WebhookSink implements EventSink {

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void accept(ArtifactStore store, RepositoryEvent event) throws IOException {
        if (!Webhooks.enabled()) {
            return;
        }
        new WebhookOutbox(store).record(event);
    }
}
