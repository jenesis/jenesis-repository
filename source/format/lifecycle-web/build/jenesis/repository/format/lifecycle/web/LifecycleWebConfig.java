package build.jenesis.repository.format.lifecycle.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the version-lifecycle web adapter into the server: {@link LifecycleController} over {@link Repositories} and
 * the discovered {@link AuditTrail}, an explicit {@code @Bean} so no component scan crosses the module boundary.
 * Imported through {@code ServerModuleProvider} discovery ({@link LifecycleWebModule}); without this module there is no
 * {@code /api/lifecycle}.
 */
@Configuration(proxyBeanMethods = false)
public class LifecycleWebConfig {

    /** The marks every operator surface reads and changes them through - this API and the console's page. */
    @Bean
    public LifecycleMarks lifecycleMarks(Repositories repositories, AuditTrail audit) {
        return new LifecycleMarks(repositories.root(), audit);
    }

    @Bean
    public LifecycleController lifecycleController(LifecycleMarks marks, RepositoryRouting routing) {
        return new LifecycleController(marks, routing);
    }
}
