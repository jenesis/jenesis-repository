package build.jenesis.repository.management.web;

import java.util.Objects;
import java.util.Arrays;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Tenants;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the management web adapter into the repository server: the {@link ManagementController} over
 * {@link Repositories}, the shared {@link Authorization} and the discovered {@link AuditTrail}, beside its admin peers
 * - {@link SpiCatalogController}, {@link StoragePurgeController}, {@link WalksAdminController} - each an explicit
 * {@code @Bean}, so no component scan crosses the module boundary. Imported through {@code ServerModuleProvider}
 * discovery ({@link ManagementWebModule}), so without this module the server carries none of these endpoints and the
 * console hides the panels.
 */
@Configuration(proxyBeanMethods = false)
public class ManagementWebConfig {

    @Bean
    public ManagementController managementController(Repositories repositories, RepositoryRouting routing,
                                                     Authorization authorization, AuditTrail audit,
                                                     LiveConfig live, SettingsEditor editor) {
        return new ManagementController(repositories, routing, authorization, audit, live, editor);
    }


    @Bean
    public SpiCatalogController spiCatalogController(Settings settings, Environment environment, PinnedSettings pins) {
        return new SpiCatalogController(settings, environment, pins);
    }

    @Bean
    public PostureAdminController postureAdminController(Settings settings, Environment environment,
                                                         PinnedSettings pins) {
        // Every discovered SafetyAdvisor against the effective configuration, through the chain the running server
        // resolves its dials through: an operator's pin over the stored settings over the environment.
        return new PostureAdminController(settings, environment, pins);
    }

    @Bean
    public ObservabilityAdminController observabilityAdminController(ConfigurableListableBeanFactory beans) {
        // This context's report: the discovered sources and every source among its singletons.
        return new ObservabilityAdminController(() -> ObservabilityReport.of(Arrays.stream(beans.getSingletonNames()).map(beans::getSingleton).filter(Objects::nonNull).toList()));
    }

    @Bean
    public CachesAdminController cachesAdminController(AuditTrail audit, Authorization authorization,
                                                      RepositoryRouting routing) {
        // Registered by hand like every controller here; the controller-registration inspection rule holds that it is.
        return new CachesAdminController(audit, authorization, routing);
    }

    @Bean
    public StoragePurgeController storagePurgeController(StorageNamespaces namespaces, Tenants tenants, AuditTrail audit,
                                                        RepositoryRouting routing) {
        return new StoragePurgeController(namespaces, tenants, audit, routing);
    }

    /** The discovery check over the API, registered by hand like every controller here; the reader is the server's,
     *  so a composition without one answers 501. */
    @Bean
    public DiscoveryAdminController discoveryAdminController(ObjectProvider<RepositoryDiscovery> discovery) {
        return new DiscoveryAdminController(discovery);
    }

    /** The walks over the API, registered by hand like every controller here: absent, {@code GET /api/admin/walks} and
     *  {@code POST /api/admin/walks/run}, which the CLI calls, would answer 404. */
    @Bean
    public WalksAdminController walksAdminController(ArtifactStore root, AuditTrail audit, Settings settings,
                                                     PinnedSettings pinned, Environment environment,
                                                     MaintenanceScheduler maintenance,
                                                     RepositoryRouting routing) {
        return new WalksAdminController(root, audit, settings, pinned, environment, maintenance, routing);
    }
}
