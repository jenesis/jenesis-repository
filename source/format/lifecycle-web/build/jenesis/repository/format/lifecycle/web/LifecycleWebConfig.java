package build.jenesis.repository.format.lifecycle.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the version-lifecycle web adapter into the repository server: the {@link LifecycleController} over the
 * framework-free {@link Repositories} resolver and the discovered {@link AuditTrail}, registered as an explicit
 * {@code @Bean} - Spring MVC maps the {@code @RestController} handler methods on the bean instance, so no component scan
 * crosses the module boundary. Imported through {@code ServerModuleProvider} discovery
 * (see {@link LifecycleWebModule}), never named by the server - so with this module absent the server carries no
 * {@code /api/lifecycle} endpoint. The bean mirrors the constructor injection the monolith performed, so the resolved
 * dependencies are the same ones the server already exposes.
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
