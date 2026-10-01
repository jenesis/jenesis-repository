/**
 * Event webhooks as a plugin module: the discovered {@link build.jenesis.repository.events.EventSink} seam becomes
 * per-tenant outbound HTTP callbacks, so CI, chat or a SIEM reacts to a publish, quarantine, finding or promotion
 * without polling. The {@link build.jenesis.repository.webhook.WebhookSink} only writes a note in a store-backed
 * {@link build.jenesis.repository.webhook.WebhookOutbox}, and a
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code webhook} drains it under an
 * exclusive lease: HMAC-SHA256 signed, filtered by event type, with exponential backoff and a terminal park, at least
 * once. The payload is small metadata and the endpoints a per-tenant setting.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 */
module build.jenesis.repository.webhook {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.store;
    // Transitive: this module's outbox is an outbox.Outbox, whose nested types consumers name.
    requires transitive build.jenesis.repository.outbox;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires java.net.http;
    requires tools.jackson.databind;
    exports build.jenesis.repository.webhook;
    provides build.jenesis.repository.events.EventSink
            with build.jenesis.repository.webhook.WebhookSink;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.webhook.WebhookDeliveryTaskProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.webhook.WebhookStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.webhook.WebhookSettingsContributor;
}
