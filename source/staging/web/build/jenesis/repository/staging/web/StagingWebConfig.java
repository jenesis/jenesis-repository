package build.jenesis.repository.staging.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link StagingController} over the {@link Repositories} resolver. */
@Configuration(proxyBeanMethods = false)
public class StagingWebConfig {

    @Bean
    public StagingController stagingController(Repositories repositories, RepositoryRouting routing,
                                               AuditTrail audit) {
        return new StagingController(repositories, routing, audit);
    }
}
