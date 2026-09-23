package build.jenesis.repository.management.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Tenants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the credential and authorization management web adapter into the repository server: the
 * {@link ManagementController} over the framework-free {@link Repositories} resolver, the shared {@link Authorization}
 * and the discovered {@link AuditTrail}, alongside its re-homed admin peers - the
 * {@link SpiCatalogController} plug-in read, the {@link StoragePurgeController} storage-manifest reclamation and
 * the {@link WalksAdminController} walks overview - each
 * registered as an explicit {@code @Bean} (Spring MVC maps the {@code @RestController} handler methods on the bean
 * instances, so no component scan crosses the module boundary). Imported through
 * {@code ServerModuleProvider} discovery (see {@link ManagementWebModule}), never named by the server - so with this
 * module absent the server carries no credential, policy, quota, rate-limit, trust, role, audit, token-exchange,
 * SPI-catalogue or storage-purge endpoint and the console hides the panels. The beans mirror the constructor injection
 * the monolith performed, so the resolved dependencies are the same ones the server already exposes.
 */
@Configuration(proxyBeanMethods = false)
public class ManagementWebConfig {

    @Bean
    public ManagementController managementController(Repositories repositories, Authorization authorization,
                                                     AuditTrail audit) {
        return new ManagementController(repositories, authorization, audit);
    }


    @Bean
    public SpiCatalogController spiCatalogController(Settings settings, Environment environment, PinnedSettings pins) {
        return new SpiCatalogController(settings, environment, pins);
    }

    @Bean
    public PostureAdminController postureAdminController(Settings settings, Environment environment,
                                                         PinnedSettings pins) {
        // The security-posture read: every discovered SafetyAdvisor's advisory against the effective config -
        // an operator's PinnedSettings pin over the kernel's Settings over the deployment Environment, the chain the
        // running server resolves its own dials through. A clean deployment returns an empty list.
        return new PostureAdminController(settings, environment, pins);
    }

    @Bean
    public ObservabilityAdminController observabilityAdminController() {
        // The running server renders the ServiceLoader-discovered sources; a disabled/absent source contributes
        // nothing, so the read degrades to whatever is actually installed - the same seam /actuator/observability uses.
        return new ObservabilityAdminController(ObservabilityReport::discover);
    }

    @Bean
    public CachesAdminController cachesAdminController(AuditTrail audit, Authorization authorization,
                                                      RepositoryProperties properties) {
        // Written, documented, reachable from the CLI - and registered by nothing until 2026-09-09, so
        // /api/admin/caches and its clear answered 404 in every composition while the console's own button worked.
        // The controller-registration inspection rule exists because of this one and the walks one before it.
        return new CachesAdminController(audit, authorization, properties);
    }

    @Bean
    public StoragePurgeController storagePurgeController(StorageNamespaces namespaces, Tenants tenants, AuditTrail audit,
                                                        RepositoryProperties properties) {
        return new StoragePurgeController(namespaces, tenants, audit, properties);
    }

    /**
     * The walks over the API.
     *
     * <p>Registered as of 2026-09-08, having never been: the controller was written and documented as the API half
     * of the walks capability, and this config registers its controllers by hand, so being absent from the list
     * meant {@code GET /api/admin/walks} and {@code POST /api/admin/walks/run} answered 404 in every composition
     * that carried the module. The CLI's {@code walks} and {@code walks run} call exactly those two paths, so two
     * of that capability's three surfaces were dead while the third - the console screen - worked.
     */
    @Bean
    public WalksAdminController walksAdminController(ArtifactStore root, AuditTrail audit, Settings settings,
                                                     MaintenanceScheduler maintenance,
                                                     RepositoryProperties properties) {
        return new WalksAdminController(root, audit, settings, maintenance, properties);
    }
}
