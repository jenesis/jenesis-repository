package build.jenesis.repository.cleanup.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.SettingsEditor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the repository-maintenance web adapter into the server: {@link MaintenanceController} over {@link Repositories}
 * and the {@link ObservationRegistry} the sweep is observed through. Imported through {@code ServerModuleProvider}
 * discovery ({@link MaintenanceWebModule}); without this module the server carries no retention, cleanup or pin
 * endpoints.
 */
@Configuration(proxyBeanMethods = false)
public class MaintenanceWebConfig {

    @Bean
    public MaintenanceController maintenanceController(Repositories repositories, RepositoryRouting routing,
                                                       LiveConfig live, SettingsEditor editor,
                                                       ObservationRegistry observations, AuditTrail audit,
                                                       MaintenanceScheduler maintenance) {
        return new MaintenanceController(repositories, routing, live, editor, observations, audit, maintenance);
    }
}
