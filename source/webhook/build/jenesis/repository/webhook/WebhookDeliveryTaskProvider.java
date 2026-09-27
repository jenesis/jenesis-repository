package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the webhook drain: enabled by the {@code webhook} setting, its cadence from {@code webhook-interval}
 * (default a minute, so a callback is timely), its retry cap from {@code webhook-attempts} (default five, after
 * which a failing delivery parks). Off unless enabled, so a deployment delivers no webhooks until it opts in - and a
 * deployment without this module has no webhooks at all. Creating the task is also where the feature's enablement is
 * latched into {@link Webhooks} (so the sink records outbox entries only when webhooks are on).
 *
 * <p>The cadence is held as an {@link IntervalSetting} constant and rendered into {@link WebhookSettingsContributor}
 * from it, so the catalogue default cannot drift from the code.
 *
 * <p><b>"Off unless enabled" is what the gate reads, not only what the sentence says.</b> The two-argument
 * {@link Features#enabled(UnaryOperator, String)} answers on for a key nobody has stored, so reading it would run the
 * drain on every deployment, and one that had configured no endpoint would have each publish write an outbox note
 * that the next minute's drain deleted - a cost that shows up only as a moving per-object figure in the walk. The
 * enablement says {@code false} in both places, and the three-argument form is the one to read for any key the
 * catalogue defaults off - which is precisely the drift that form exists to prevent.
 */
public final class WebhookDeliveryTaskProvider implements MaintenanceTaskProvider {

    /** How often the webhook outbox drains; a minute by default, so a callback is timely. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("webhook-interval", "PT1M");

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        // The three-argument form, because the catalogue defaults this key OFF. The two-argument one answers ON
        // for an unstored key and would put the code and the catalogue back into disagreement.
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
