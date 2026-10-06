package build.jenesis.repository.ui.admin.config;

import module java.base;

import build.jenesis.repository.ui.GithubCredentials;
import build.jenesis.repository.ui.admin.CacheDemo;
import build.jenesis.repository.ui.PostureSource;
import build.jenesis.repository.ui.SpiCatalogSource;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.store.CacheClear;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.store.CredentialService;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.GrantableRights;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.RepositoryImports;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.ui.store.VolumeReclaim;
import org.slf4j.LoggerFactory;
import io.micrometer.observation.ObservationRegistry;
import build.jenesis.repository.compliance.ComplianceSources;
import build.jenesis.repository.search.SearchQueryProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.RepositoryRoutingProvider;

/**
 * Wires the Spring-free {@code build.jenesis.repository.ui.store} service layer into the console's context: each
 * service is a bean given the collaborators the console configures and the two web-bound seams ({@code CurrentTenant},
 * {@code ConsoleActor}), so the console calls the domain in process. The grantable-rights surfaces are beans too, which
 * {@code CredentialService} validates a role against.
 */
@Configuration
public class DomainConfig {

    // The audit trail comes from RepositoryStoreConfig, or from the repository in a composed node, never from here:
    // @ConditionalOnMissingBean between two plain @Configuration classes of one scan is evaluated in an undefined
    // order, and two auditTrail beans would stop the context.

    @Bean
    public CacheService cacheService(@Qualifier("cacheRootStorage") CacheStorage cacheRootStorage,
                                     AuditTrail audit, CurrentTenant currentTenant, ConsoleActor actor,
                                     SettingsAdmin settingsAdmin) {
        // Cached build output lives in the cache's own segment of the store, not at the deployment root; the service
        // scopes it to the selected tenant on each call, so a pass it starts keeps the tenant past the request.
        return new CacheService(cacheRootStorage, audit, currentTenant, actor, settingsAdmin);
    }

    /** The build cache's sample projects, which the first-run demo loads beside the repositories' content. */
    @Bean
    public CacheDemo cacheDemo(@Qualifier("cacheRootStorage") CacheStorage cacheRootStorage,
                               ArtifactStore repositoryStore, SettingsEditor settingsEditor, AuditTrail audit) {
        return new CacheDemo(cacheRootStorage, repositoryStore, settingsEditor, audit);
    }

    @Bean
    public TenantService tenantService(@Qualifier("rootStorage") Documents rootStorage, AuditTrail audit,
                                       ConsoleActor actor) {
        // Audited: create records tenant.create in the new tenant's scope.
        return new TenantService(rootStorage, audit, actor);
    }

    @Bean
    public TenantPurge tenantPurge(TenantService tenantService, ArtifactStore repositoryStore,
                                   Authorization authorization, AuditTrail audit, ConsoleActor actor,
                                   ConfigurableEnvironment environment) {
        // The purge deletes the tenant's audit space, so its event goes to the operator scope.
        return new TenantPurge(tenantService, repositoryStore, authorization, audit, actor,
                operatorTenant(environment));
    }

    @Bean
    public VolumeReclaim volumeReclaim(@Qualifier("cacheRootStorage") CacheStorage cacheRootStorage,
                                       AuditTrail audit, ConsoleActor actor, ConfigurableEnvironment environment) {
        // Spans the cache's root, so it can never walk the repositories for things to delete.
        return new VolumeReclaim(cacheRootStorage, audit, actor, operatorTenant(environment));
    }

    @Bean
    public CacheClear cacheClear(Authorization authorization, AuditTrail audit, ConsoleActor actor,
                                 ConfigurableEnvironment environment) {
        // What a clear drops belongs to no tenant, so it is recorded in the operator scope.
        return new CacheClear(authorization, audit, actor, operatorTenant(environment));
    }

    /**
     * A fixed deployment's one tenant ({@code jenrepo.default-tenant}), created at boot when the store lacks it, since
     * the console lists tenants from the store and a fresh store would otherwise show none. A multi-tenant or read-only
     * deployment is left alone.
     */
    @Bean
    public FixedTenant fixedTenant(TenantService tenants, Tenancy tenancy,
                                   ConfigurableEnvironment environment) {
        if (!tenancy.fixed() || environment.getProperty("jenrepo.read-only", Boolean.class, false)) {
            return new FixedTenant(null);
        }
        String tenant = environment.getProperty("jenrepo.default-tenant", Scopes.DEFAULT_TENANT);
        try {
            if (!tenants.exists(tenant)) {
                tenants.create(tenant);
            }
        } catch (IllegalArgumentException raced) {
            // Another node created it between the check and the write - which is the outcome wanted.
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(DomainConfig.class).warn("The deployment's tenant '{}' could not be created; the "
                    + "console lists it once anything is written under it", tenant, e);
        }
        return new FixedTenant(tenant);
    }

    /** The tenant a fixed deployment serves, or {@code null} where the deployment names its own tenants. */
    public record FixedTenant(String name) {
    }

    /** How the deployment routes tenants, read once for the console. */
    @Bean
    public Tenancy tenancy(ConfigurableEnvironment environment) {
        return new Tenancy(environment.getProperty("jenrepo." + RepositoryRoutingProvider.SETTING,
                RepositoryRoutingProvider.FIXED).equals(RepositoryRoutingProvider.FIXED));
    }

    /** Whether the deployment serves one tenant ({@code jenrepo.tenancy=fixed}) or several. */
    public record Tenancy(boolean fixed) {

        public boolean multi() {
            return !fixed;
        }
    }

    /** The operator tenant ({@code operator-tenant}, else {@code default-tenant}, else {@link Scopes#DEFAULT_TENANT}),
     *  where privileged mutations belonging to no single tenant are audited. */
    private static String operatorTenant(ConfigurableEnvironment environment) {
        String operatorTenant = environment.getProperty("jenrepo.operator-tenant", "");
        return operatorTenant.isBlank()
                ? environment.getProperty("jenrepo.default-tenant", Scopes.DEFAULT_TENANT)
                : operatorTenant;
    }

    @Bean
    public SettingsAdmin settingsAdmin(ArtifactStore repositoryStore, ConfigurableEnvironment environment,
                                       SettingsEditor settingsEditor, TenantService tenantService, AuditTrail audit,
                                       CurrentTenant currentTenant, ConsoleActor actor) {
        // The settings editor every surface uses, in process. Deploy-time keys such as JENREPO_SECRETS_KEY come from
        // the console's environment, so a credential stored through the console is sealed under the API's key.
        return new SettingsAdmin(repositoryStore, settingsEditor, tenantService::all, audit, currentTenant, actor,
                Features.namespaced(environment::getProperty));
    }

    /** The GitHub OAuth app the console signs in with, kept as the console's settings. */
    @Bean
    public GithubCredentials githubCredentials(SettingsAdmin settings) {
        return new StoredGithubApp(settings);
    }

    @Bean
    public RepositoryAdmin repositoryAdmin(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                           ObservationRegistry observations) {
        return new RepositoryAdmin(repositoryStore, currentTenant, observations);
    }

    /** The format family's mark lookup, shared by every console surface and memoized, since discovery is fixed for the
     *  JVM's life. */
    @Bean
    public FormatMarks formatMarks() {
        return FormatMarks.installed();
    }

    @Bean
    public RepositoryBrowse repositoryBrowse(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                             ObservationRegistry observations,
                                             ObjectProvider<ComplianceSources> sources,
                                             SettingsAdmin settingsAdmin) {
        // The feeds the repository screens with, where this node holds them, so a cached copy's page names what
        // screened it - those its repository selects; a console booted alone holds none and says nothing of it.
        return new RepositoryBrowse(repositoryStore, currentTenant, observations, SearchQueryProvider.installed(),
                () -> Optional.ofNullable(sources.getIfAvailable()).map(ComplianceSources::advisoryFeeds),
                (tenant, repository) -> {
                    try {
                        return settingsAdmin.repositoryConfig(tenant, repository);
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException(unreadable);
                    }
                });
    }


    @Bean
    public TenantLimits tenantLimits(ArtifactStore repositoryStore, SettingsAdmin settingsAdmin,
                                     CurrentTenant currentTenant, ObservationRegistry observations,
                                     AuditTrail audit, ConsoleActor actor) {
        return new TenantLimits(repositoryStore, settingsAdmin, currentTenant, observations, audit, actor);
    }

    @Bean
    public RepositoryImports repositoryImports(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                               ObservationRegistry observations, AuditTrail audit, ConsoleActor actor,
                                               SettingsEditor settingsEditor) {
        return new RepositoryImports(repositoryStore, currentTenant, observations, audit, actor, settingsEditor);
    }

    @Bean
    public RepositoryLifecycle repositoryLifecycle(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                                   ObservationRegistry observations, AuditTrail audit,
                                                   ConsoleActor actor, SettingsAdmin settingsAdmin) {
        return new RepositoryLifecycle(repositoryStore, currentTenant, observations, audit, actor, settingsAdmin);
    }

    @Bean
    public CredentialService credentialService(Authorization authorization, AuditTrail audit,
                                               CurrentTenant currentTenant, ConsoleActor actor,
                                               List<GrantableRights> rights) {
        return new CredentialService(authorization, audit, currentTenant, actor, rights);
    }

    @Bean
    public GrantableRights cacheRights() {
        return new GrantableRights.CacheRights();
    }

    @Bean
    public GrantableRights repositoryRights() {
        return new GrantableRights.RepositoryRights();
    }

    @Bean
    public GrantableRights managementRights() {
        return new GrantableRights.ManagementRights();
    }

    /**
     * Where the posture screen and badge read from: the stored settings layered over the environment, for the tenant
     * asked about.
     */
    @Bean
    public PostureSource postureSource(SettingsAdmin settings, ConfigurableEnvironment environment) {
        return tenant -> {
            SettingsAdmin.CollectedPosture collected = settings.posture(tenant, environment::getProperty);
            return new PostureSource.Collected(collected.posture(), collected.collectedAt());
        };
    }

    /**
     * This console's catalogue: the module graph decorated with each implementation's effective state - installed,
     * gate open, which key opens it, which settings it contributes.
     */
    @Bean
    public SpiCatalogSource spiCatalogSource(SettingsAdmin settings) {
        return settings::catalog;
    }
}
