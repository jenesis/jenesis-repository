package build.jenesis.repository.webhook.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link WebhookController} over the stores {@link Repositories} resolves, auditing a retry through the
 *  {@link AuditTrail}. */
@Configuration(proxyBeanMethods = false)
public class WebhookWebConfig {

    @Bean
    public WebhookController webhookController(Repositories repositories, RepositoryRouting routing, AuditTrail audit) {
        return new WebhookController(repositories, routing, audit);
    }
}
