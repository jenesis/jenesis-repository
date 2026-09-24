package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The discovered {@link EventSink} that turns a repository event into a queued webhook: it only leaves a note in the
 * event's own scoped {@link WebhookOutbox}, so emission stays fire-and-forget and never blocks the producer - the
 * latency-bearing HTTP delivery is the {@link WebhookDeliveryTask}'s. It is this module's <em>only</em> entry point
 * from the seam: every {@code EventType}, publish and unpublish included, arrives here through
 * {@link EventSink#emit} rather than through a producer of this module's own. It records nothing when the
 * feature is off
 * ({@link Webhooks#enabled()}), so a deployment that never turns webhooks on writes no outbox state at all; when on
 * but a repository has no configured endpoint, the note is still written and the very next drain drops it, so the
 * misconfiguration self-cleans rather than leaking.
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
