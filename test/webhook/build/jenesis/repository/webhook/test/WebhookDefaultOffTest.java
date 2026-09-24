package build.jenesis.repository.webhook.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.webhook.WebhookDeliveryTaskProvider;
import build.jenesis.repository.webhook.WebhookSettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook drain is off on a deployment that has not asked for it - in the catalogue and in the gate alike.
 *
 * <p>It was not. The provider's own javadoc said "off unless enabled, so a deployment delivers no webhooks until
 * it opts in", while the catalogue declared the key {@code true} and the gate read the two-argument
 * {@code Features.enabled}, which answers on for a key nobody has stored. Every deployment therefore ran the
 * drain, and one with no endpoint configured had each publish write an outbox note for the next minute's drain to
 * delete - a write and a delete per publish, for a feature nobody had turned on. It was found as a cost
 * measurement rather than as a webhook failure: a maintenance pass's per-object figure moved on whichever object
 * store's measurement window that minute elapsed in, and was recorded for three days as a difference between
 * backends.
 *
 * <p>Both halves are asserted because either alone would have passed while they disagreed: a catalogue default is
 * what the settings screen, the generated reference and an operator see, and the gate is what the node does.
 */
class WebhookDefaultOffTest {

    @Test
    void the_catalogue_declares_the_feature_off() {
        Setting declared = new WebhookSettingsContributor().settings().stream()
                .filter(setting -> "webhook".equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the webhook catalogue no longer declares the 'webhook' key"));
        assertThat(declared.defaultValue())
                .as("what the settings screen and the generated reference show for an untouched deployment")
                .isEqualTo("false");
    }

    @Test
    void a_deployment_that_stored_nothing_installs_no_drain() {
        // The empty config is the whole point: a stored value would prove nothing about the default.
        assertThat(new WebhookDeliveryTaskProvider().create(key -> null))
                .as("the drain a deployment gets without asking for webhooks")
                .isEmpty();
    }

    @Test
    void an_operator_who_asks_for_it_gets_it() {
        // The other half of a default: it must still be reachable, or "off" would be "gone".
        assertThat(new WebhookDeliveryTaskProvider().create(Map.of("webhook", "true")::get))
                .as("the drain an operator turns on")
                .isPresent();
    }
}
