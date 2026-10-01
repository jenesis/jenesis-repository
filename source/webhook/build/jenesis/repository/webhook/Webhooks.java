package build.jenesis.repository.webhook;

import module java.base;

/**
 * The webhook feature's boot-time enablement latch, read by the discovered {@link WebhookSink} on the request path and
 * by the {@link WebhookDeliveryTask}, set by a scheduler pass. Off by default, so a deployment that never enables
 * webhooks writes no webhook state. Read here rather than by a producer, which would decide for every other installed
 * sink too.
 *
 * <p>Enablement is the {@code webhook} flag alone: endpoints are a per-tenant dial the deployment-global boot value
 * cannot see, so an enabled deployment without endpoints queues events the next drain drops.
 */
public final class Webhooks {

    /** Immutability exception, a boot-time latch: set once per scheduler boot and read lock-free. Process-wide because
     *  it is the process's own configuration, carrying no deployment's collaborators. */
    private static volatile boolean enabled;

    private Webhooks() {
    }

    /** Record the enablement, read once per scheduler boot from the {@code webhook} flag; without a scheduler pass it
     *  stays off and no outbox object is written. */
    public static void configure(boolean on) {
        enabled = on;
    }

    /** Whether the feature is enabled - the gate the sink checks before recording an event. */
    public static boolean enabled() {
        return enabled;
    }
}
