package build.jenesis.repository.format.lifecycle.web;

import build.jenesis.repository.audit.AuditTrail;
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

    @Bean
    public LifecycleController lifecycleController(Repositories repositories, AuditTrail audit) {
        return new LifecycleController(repositories, audit);
    }
}
