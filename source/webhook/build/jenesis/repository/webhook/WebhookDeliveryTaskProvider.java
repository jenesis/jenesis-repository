package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the webhook drain: enabled by the {@code webhook} setting, paced by {@code webhook-interval} (a minute),
 * its retry cap {@code webhook-attempts} (five, after which a delivery parks). Off unless enabled. Creating the task
 * latches the enablement into {@link Webhooks}, so the sink records notes only when webhooks are on. The cadence is an
 * {@link IntervalSetting} rendered into {@link WebhookSettingsContributor}.
 */
public final class WebhookDeliveryTaskProvider implements MaintenanceTaskProvider {

    /** How often the outbox drains: a minute, so a callback is timely. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("webhook-interval", "PT1M");

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        // The three-argument form, since the catalogue defaults this key off and the two-argument one answers on for an
        // unstored key.
        boolean on = Features.enabled(config, "webhook", false);
        Webhooks.configure(on);
        if (!on) {
            return Optional.empty();
        }
        int attempts = parse(config.apply("webhook-attempts"), 5);
        return Optional.of(new WebhookDeliveryTask(INTERVAL.resolve(config),
                Math.max(1, attempts), Duration.ofSeconds(30), Duration.ofHours(6),
                WebhookDelivery.live()));
    }

    private static int parse(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
