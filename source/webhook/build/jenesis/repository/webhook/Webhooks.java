package build.jenesis.repository.webhook;

import module java.base;

/**
 * The webhook feature's boot-time enablement latch, kept in this framework-free module so the discovered {@link
 * WebhookSink} (firing on the request path, for every event the seam fans out) and the {@link WebhookDeliveryTask}
 * (firing on the maintenance scheduler) read the same on/off state a scheduler pass sets - the pattern forwarding and
 * the compliance gate use. Everything is inert by default: with the feature disabled the sink writes no outbox note,
 * so a deployment that never turns webhooks on writes no webhook state at all. The latch is deliberately read here
 * rather than by a producer: whether a notification is wanted is this delivery module's own question, and a producer
 * that consulted it would silently decide for every other installed sink too.
 *
 * <p>Unlike forwarding's target-gated latch, enablement here is the {@code webhook} flag alone, not the presence of
 * a configured endpoint: endpoints are a <em>per-tenant</em> dial, so whether any endpoint exists cannot be decided
 * from the deployment-global value the scheduler sees at boot. An enabled-but-unconfigured deployment therefore
 * queues events that the very next drain drops (a repository whose effective endpoints are empty accumulates no
 * outbox state), so the misconfiguration is self-cleaning and bounded by the drain interval rather than a leak.
 */
public final class Webhooks {

    /**
     * §11 exception - boot-time enablement latch: set once per scheduler boot via configure(boolean) and read
     * lock-free by the webhook sink and task. Feature wiring, not shared mutable data.
     */
    private static volatile boolean enabled;

    private Webhooks() {
    }

    /** Record the feature's enablement, read once per scheduler boot from the {@code webhook} flag: the sink leaves
     *  an outbox note only when webhooks are on. Absent a scheduler pass the flag stays false and no outbox object is
     *  ever written. */
    public static void configure(boolean on) {
        enabled = on;
    }

    /** Whether the feature is enabled - the gate the sink checks before recording an event. */
    public static boolean enabled() {
        return enabled;
    }
}
