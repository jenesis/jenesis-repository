/**
 * The webhook recovery HTTP surface, contributed through
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}: a thin adapter over
 * {@link build.jenesis.repository.webhook.WebhookOutbox} that reports one repository's queued and parked deliveries
 * ({@code GET /api/webhook}, {@code manage:read}) and unparks a parked one ({@code POST /api/webhook/retry},
 * {@code manage:write}, audited). The background {@link build.jenesis.repository.webhook.WebhookDeliveryTask} does the
 * delivering, so a retry never re-sends to an endpoint that already took the event. Open so Spring can reflect over the
 * controller and its configuration.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.webhook.web {
    exports build.jenesis.repository.webhook.web;
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
