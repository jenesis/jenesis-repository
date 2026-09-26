/**
 * Event webhooks as a plugin module: it turns the discovered {@link build.jenesis.repository.events.EventSink}
 * seam into per-tenant outbound HTTP callbacks, so an external system (CI, chat, a SIEM) reacts to a publish,
 * quarantine, finding or promotion without polling. It is a pure <em>delivery</em> module: every event reaches it
 * through the seam's own {@code emit} fan-out, including the publish and unpublish legs it used to produce for itself
 * out of a {@link build.jenesis.repository.store.PublicationObserver} of its own (moved that producer beside the
 * seam, where every installed sink can see it). It never blocks the request path - the {@link
 * build.jenesis.repository.webhook.WebhookSink} it provides only leaves a small
 * note in a store-backed {@link build.jenesis.repository.webhook.WebhookOutbox} under the event's own scoped store
 * (store-only state, no database) - and a discovered {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider}
 * answering to {@code webhook} drains it under an exclusive lease, delivering each queued event to the tenant's
 * configured endpoints over {@code java.net.http} with an HMAC-SHA256 signature, an event-type filter, exponential
 * backoff and a terminal park - at-least-once and idempotent, the delivered-endpoint set kept per entry so a retry
 * re-sends only to the endpoints that never took it. The event payload is small metadata (never an artifact body),
 * and the endpoints are one per-tenant {@link build.jenesis.repository.settings.SettingsContributor} dial, so a
 * deployment without this module emits nothing and a deployment with it but no configured endpoint delivers
 * nothing and accumulates no outbox state.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 */
module build.jenesis.repository.webhook {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.store;
    // transitive: this module's Outbox IS an outbox.Outbox, so its Queued and Window are the shared
    // module's nested types and every consumer that names them must read it.
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
