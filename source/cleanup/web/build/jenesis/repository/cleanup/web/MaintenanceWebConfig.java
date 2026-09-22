package build.jenesis.repository.cleanup.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.LiveConfig;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the repository-maintenance web adapter into the repository server: the {@link MaintenanceController} over the
 * framework-free {@link Repositories} resolver and the {@link ObservationRegistry} the cleanup sweep is observed
 * through. Imported through {@code ServerModuleProvider} discovery (see {@link MaintenanceWebModule}), never named by
 * the server - so with this module absent the server carries no retention, cleanup or pin endpoints. The bean mirrors
 * the constructor injection the monolith performed, so the resolved dependencies are the same ones the server already
 * exposes.
 */
@Configuration(proxyBeanMethods = false)
public class MaintenanceWebConfig {

    @Bean
    public MaintenanceController maintenanceController(Repositories repositories, LiveConfig live,
                                                       ObservationRegistry observations, AuditTrail audit,
                                                       MaintenanceScheduler maintenance) {
        return new MaintenanceController(repositories, live, observations, audit, maintenance);
    }
}
