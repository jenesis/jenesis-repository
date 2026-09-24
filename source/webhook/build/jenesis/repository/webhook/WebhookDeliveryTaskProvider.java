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
 * <p><b>"Off unless enabled" was the sentence above and not what shipped.</b> The catalogue declared the key
 * {@code true} and this gate read the two-argument {@link Features#enabled(UnaryOperator, String)}, which answers
 * on for a key nobody has stored - so every deployment ran the drain, and one that had configured no endpoint had
 * each publish write an outbox note that the next minute's drain deleted. It surfaced as a cost measurement: the
 * walk's per-object figure moved on whichever backing's window that minute elapsed in, and was recorded for three
 * days as a difference between object stores. The enablement now says {@code false} in both places, and the
 * three-argument form is the one to read for any key the catalogue defaults off - which is precisely the drift
 * that form exists to prevent.
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
