package build.jenesis.repository.webhook.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the event-webhook recovery web adapter into the repository server: the {@link WebhookController} over the
 * framework-free {@link build.jenesis.repository.webhook.WebhookOutbox} resolved per tenant through {@link Repositories},
 * writing an audit event on a retry through the discovered {@link AuditTrail}. Imported through {@code ServerModuleProvider}
 * discovery (see {@link WebhookWebModule}), never named by the server, so with this module absent the server carries no
 * webhook recovery surface. The delivering is the webhook core's own background {@code WebhookDeliveryTask}; this adapter
 * only exposes the status read and the unpark write, it does not re-implement delivery.
 */
@Configuration(proxyBeanMethods = false)
public class WebhookWebConfig {

    @Bean
    public WebhookController webhookController(Repositories repositories, AuditTrail audit) {
        return new WebhookController(repositories, audit);
    }
}
