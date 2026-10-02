package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the webhook settings. The endpoints dial is {@link Setting.Scope#TENANT tenant-scoped}, a tenant
 * registering its own callbacks; the switch, cadence and retry cap are deployment-global. The {@code webhook} flag is
 * the module's {@link Setting#gate() gate}. The cadence renders from {@link WebhookDeliveryTaskProvider}'s constant.
 */
public final class WebhookSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("webhook", "Webhooks", "Event webhooks",
                        "Deliver HTTP callbacks when an artifact is published or unpublished, the gate quarantines "
                                + "one, a hold is released or discarded, a finding is recorded or a staged set is "
                                + "promoted. Delivery is at-least-once, so a receiver must tolerate a repeat, and an "
                                + "event can be lost before it is queued: a subscriber that cannot miss one treats a "
                                + "callback as a prompt to read the durable ledger GET /api/webhook names. Leave it "
                                + "off where there is no receiver.",
                        Setting.Kind.BOOLEAN, "false", false).gate().standard(),
                new Setting("webhook-endpoints", "Webhooks", "Webhook endpoints",
                        "The endpoints events are delivered to, each an https URL, optionally with the events it "
                                + "receives, and every event where it names none. An http endpoint is refused at "
                                + "delivery unless webhook-allow-internal is set. Give every endpoint a signing secret "
                                + "in webhook-secrets, keyed by its URL: an endpoint with none is delivered unsigned, "
                                + "and its receiver cannot tell a genuine event from one forged by anyone who learns "
                                + "the URL.",
                        Setting.Kind.STRING, "", false, Setting.Scope.TENANT).form(Setting.Form.LINES).standard(),
                new Setting("webhook-secrets", "Webhooks", "Webhook signing secrets",
                        "Per-endpoint HMAC-SHA256 signing secrets, one '<https-url>=<secret>' per line, keyed by the "
                                + "endpoint URL as it appears in 'webhook-endpoints'. When an endpoint has a matching "
                                + "secret its delivery body is signed (header '" + WebhookDelivery.SIGNATURE_HEADER
                                + "'); an endpoint with NO entry is delivered unsigned, which leaves its receiver "
                                + "unable to distinguish a genuine event from a forged POST - set one for every "
                                + "endpoint unless that receiver authenticates some other way. The "
                                + "'jenrepo.webhook.unsigned' gauge counts the endpoints that have none. "
                                + "Write-only: stored as a secret, so it is redacted on read-back and kept out of the "
                                + "settings export bundle.",
                        Setting.Kind.SECRET, "", false, Setting.Scope.TENANT).standard(),
                new Setting(WebhookDeliveryTaskProvider.INTERVAL.key(), "Webhooks", "Webhook drain interval",
                        "How often the webhook outbox is drained.",
                        Setting.Kind.DURATION, WebhookDeliveryTaskProvider.INTERVAL.fallbackText(), false).advanced(),
                new Setting("webhook-attempts", "Webhooks", "Webhook retry attempts",
                        "How many times a failing delivery is retried (with exponential backoff) before it is parked.",
                        Setting.Kind.INTEGER, "5", false).advanced(),
                new Setting("webhook-allow-internal", "Webhooks", "Allow internal webhook targets",
                        "Permit webhook endpoints that resolve to a loopback, private, link-local or cloud-metadata "
                                + "address, AND plaintext http:// endpoints. Off, a callback cannot reach the "
                                + "deployment's own network or metadata service (SSRF) and cannot put event metadata "
                                + "on the wire in cleartext. It applies to the whole deployment; enable it only for a "
                                + "trusted internal receiver.",
                        Setting.Kind.BOOLEAN, "false", false).advanced());
    }
}
