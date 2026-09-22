package build.jenesis.repository.staging.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the staging web adapter into the repository server: the {@link StagingController} over the framework-free
 * {@link Repositories} resolver. Imported through {@code ServerModuleProvider} discovery (see {@link StagingModule}),
 * never named by the server - so with this module absent the server carries no staging endpoints, and with no staging
 * lifecycle module installed each endpoint answers that staging is not installed ({@code 501}).
 */
@Configuration(proxyBeanMethods = false)
public class StagingWebConfig {

    @Bean
    public StagingController stagingController(Repositories repositories, AuditTrail audit) {
        return new StagingController(repositories, audit);
    }
}
