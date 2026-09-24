/**
 * The event-webhook recovery HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its configuration
 * through {@code ServiceLoader} discovery and names no webhook endpoint. A thin Spring {@code web} adapter over the
 * framework-free {@link build.jenesis.repository.webhook.WebhookOutbox}: the {@code WebhookController} reports one
 * repository's queued and parked webhook deliveries ({@code GET /api/webhook}, gated {@code manage:read}) and unparks a
 * parked delivery for another drain attempt ({@code POST /api/webhook/retry}, gated {@code manage:write} and audited),
 * both under {@code /api/} so the security chain gates them by role before the request is reached. The retry drives the
 * webhook core's own {@code unpark}; the background {@link build.jenesis.repository.webhook.WebhookDeliveryTask} still
 * does the delivering, so a retry never re-implements delivery and never re-sends to an endpoint that already took the
 * event. With this module absent the server carries none of the surface. Open so Spring can reflect over the controller
 * and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.webhook.web {
    exports build.jenesis.repository.webhook.web to build.jenesis.repository.server.kernel.test,
            build.jenesis.repository.webhook.web.test;
    requires build.jenesis.repository.webhook;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.webhook.web.WebhookWebModule;
}
