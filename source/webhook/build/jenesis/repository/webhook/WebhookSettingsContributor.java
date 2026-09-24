package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the event-webhook settings, so they surface on the settings screens exactly when this module is
 * installed. The endpoints dial is {@link Setting.Scope#TENANT tenant-scoped} - webhooks are per-tenant, so a
 * tenant registers its own callbacks over its own artifact space - while the master switch and the drain's cadence
 * and retry cap are deployment-global. The {@code webhook} flag is the module's enablement {@link Setting#gate()
 * gate}, so the modules console pairs the module with its toggle without a maintained table.
 *
 * <p>The cadence entry renders its key and default straight off {@link WebhookDeliveryTaskProvider}'s
 * {@code IntervalSetting} constant, so the catalogue and the code cannot drift.
 */
public final class WebhookSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("webhook", "Webhooks", "Event webhooks",
                        "Deliver per-tenant HTTP callbacks over the background drain when an artifact is published or "
                                + "unpublished, the gate quarantines one, a hold is released or discarded, a finding "
                                + "is recorded, or a staged set is promoted. Delivery is at-least-once, so a receiver "
                                + "must tolerate a repeat; and an event can be lost before it is ever queued (a crash "
                                + "between the change and the note), which no retry can heal. A subscriber that "
                                + "cannot miss one polls the durable ledger for its event type instead - "
                                + "GET /api/webhook lists the read for each - and treats the callback as a prompt to "
                                + "look rather than as the record. Off unless an operator turns it on: most "
                                + "deployments have no receiver, and an enabled one with no endpoint configured "
                                + "still writes a note per publish for the next drain to delete.",
                        Setting.Kind.BOOLEAN, "false", false).gate(),
                new Setting("webhook-endpoints", "Webhooks", "Webhook endpoints",
                        "One endpoint per line or semicolon: '<https-url> [events]'. 'events' is a comma-list of "
                                + "'publish,unpublish,quarantine,release,discard,finding,promotion' or '*' (all). "
                                + "Endpoints must be https: an "
                                + "http:// endpoint is REFUSED at delivery (the refusal is recorded against the queued "
                                + "event and shown on the webhook status surface) unless the operator sets "
                                + "'webhook-allow-internal'. Give every endpoint a signing secret in "
                                + "'webhook-secrets', keyed by this URL: an endpoint with no entry is delivered "
                                + "UNSIGNED, and its receiver then cannot tell a genuine event from a POST anyone who "
                                + "learns this URL can forge.",
                        Setting.Kind.STRING, "", false, Setting.Scope.TENANT),
                new Setting("webhook-secrets", "Webhooks", "Webhook signing secrets",
                        "Per-endpoint HMAC-SHA256 signing secrets, one '<https-url>=<secret>' per line, keyed by the "
                                + "endpoint URL as it appears in 'webhook-endpoints'. When an endpoint has a matching "
                                + "secret its delivery body is signed (header '" + WebhookDelivery.SIGNATURE_HEADER
                                + "'); an endpoint with NO entry is delivered unsigned, which leaves its receiver "
                                + "unable to distinguish a genuine event from a forged POST - set one for every "
                                + "endpoint unless that receiver authenticates some other way. The "
                                + "'jenreg.webhook.unsigned' gauge counts this tenant's endpoints that have none. "
                                + "Write-only: stored as a secret, so it is redacted on read-back and kept out of the "
                                + "settings export bundle.",
                        Setting.Kind.SECRET, "", false, Setting.Scope.TENANT),
                new Setting(WebhookDeliveryTaskProvider.INTERVAL.key(), "Webhooks", "Webhook drain interval",
                        "How often the webhook outbox is drained.",
                        Setting.Kind.DURATION, WebhookDeliveryTaskProvider.INTERVAL.fallbackText(), false),
                new Setting("webhook-attempts", "Webhooks", "Webhook retry attempts",
                        "How many times a failing delivery is retried (with exponential backoff) before it is parked.",
                        Setting.Kind.INTEGER, "5", false),
                new Setting("webhook-allow-internal", "Webhooks", "Allow internal webhook targets",
                        "Permit webhook endpoints that resolve to a loopback, private, link-local or cloud-metadata "
                                + "address, AND plaintext http:// endpoints. Off by default so a per-tenant callback "
                                + "cannot reach the deployment's own network or metadata service (SSRF) and cannot put "
                                + "event metadata on the wire in cleartext; a deployment-global operator dial (the "
                                + "same one the forwarding leg uses for its own targets), enable only for a trusted "
                                + "internal receiver.",
                        Setting.Kind.BOOLEAN, "false", false));
    }
}
