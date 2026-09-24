package build.jenesis.repository.ui.admin.config;

import module java.base;

import build.jenesis.repository.ui.PostureSource;
import build.jenesis.repository.ui.SpiCatalogSource;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.store.CredentialService;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.GrantableRights;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.RepositoryImports;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.ui.store.VolumeReclaim;
import org.slf4j.LoggerFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Wires the Spring-free {@code build.jenesis.repository.ui.store} application-service layer into the console's context. The
 * domain module carries no Spring annotations, so each service is declared here as a bean and given the
 * collaborators the console configures ({@code Documents}, {@code CacheStorage}, {@code ArtifactStore},
 * {@code Authorization},
 * {@code AuditTrail}, {@code ObservationRegistry}) plus the two web-bound seams ({@code CurrentTenant} and
 * {@code ConsoleActor}, implemented in the security layer). This keeps the domain reusable by another surface
 * while the console binds it directly, in-process, rather than over the HTTP API.
 *
 * <p>The grantable-rights surfaces are contributed as beans too, so {@code CredentialService} still validates a
 * role against the discovered union and a new surface is one more bean - the console's replacement for the
 * component-scanned beans the layer used to carry.
 */
@Configuration
public class DomainConfig {

    // The audit trail is NOT declared here. It used to be, as an @ConditionalOnMissingBean(AuditTrail.class)
    // fallback returning AuditTrail.none() so a composition installing no audit implementation would still boot.
    // That condition cannot do what it says between two plain @Configuration classes: it is evaluated as the class
    // is processed, and which of two user configurations is processed first is not defined. In the console's
    // standalone node it lost the race, so both this class and RepositoryStoreConfig registered a bean called
    // auditTrail and the context refused to start at all - the opposite of the boot the fallback existed to
    // guarantee, and invisible to any lane that does not start a server.
    //
    // There is no composition that needs it. The standalone node scans RepositoryStoreConfig, which declares the
    // trail unconditionally over the store it opens; a composed node excludes that class and takes the repository's
    // own, which is authoritative there. And a deployment carrying no audit implementation is already answered a
    // layer down, by AuditTrailProvider resolving to a trail that records nothing.
    //
    // The tree was swept for the same shape afterwards: six bean names are declared by more than one user
    // configuration with one of them conditional. Four never meet - they belonged to a console node nothing
    // imported, and are deleted - and the cache node's two (`authorization`, `keyUsageTracker`) are ordered rather than
    // raced, because a configuration class processes its @ComponentScan before its @Import, so the bundle's scan
    // of the composition registers both competitors before CacheConfig is ever read. What has no order at all is
    // two classes picked up by the SAME scan, which is what this one was.

    @Bean
    public CacheService cacheService(@Qualifier("cacheTenantStorage") CacheStorage cacheTenantStorage,
                                     AuditTrail audit, CurrentTenant currentTenant, ConsoleActor actor) {
        // The cache screens manage cached build output, which lives in the cache's own segment of the store - not
        // beside the console's documents at the deployment root, which is what the primary tenantStorage sees.
        return new CacheService(cacheTenantStorage, audit, currentTenant, actor);
    }

    @Bean
    public TenantService tenantService(@Qualifier("rootStorage") Documents rootStorage, AuditTrail audit,
                                       ConsoleActor actor) {
        // The audited constructor: TenantService.create records tenant.create in the new tenant's own scope. The
        // shared read-only callers (Memberships, login authorization) only list/check and so never audit.
        return new TenantService(rootStorage, audit, actor);
    }

    @Bean
    public TenantPurge tenantPurge(TenantService tenantService, ArtifactStore repositoryStore,
                                   Authorization authorization, AuditTrail audit, ConsoleActor actor,
                                   ConfigurableEnvironment environment) {
        // The purge deletes the tenant's own audit space, so its audit event is written to the operator scope, not the
        // purged tenant's.
        return new TenantPurge(tenantService, repositoryStore, authorization, audit, actor,
                operatorTenant(environment));
    }

    @Bean
    public VolumeReclaim volumeReclaim(@Qualifier("cacheRootStorage") CacheStorage cacheRootStorage,
                                       AuditTrail audit, ConsoleActor actor, ConfigurableEnvironment environment) {
        // Reclaims cached build output across every tenant, so it spans the CACHE's root rather than the
        // deployment's - it must never be able to walk the repositories looking for things to delete.
        return new VolumeReclaim(cacheRootStorage, audit, actor, operatorTenant(environment));
    }

    /**
     * A fixed deployment's one tenant, created at boot when the store does not hold it yet.
     *
     * <p>A fixed deployment serves exactly one tenant, named by {@code jenreg.default-tenant}, and the console lists
     * tenants from the store - where a tenant appears only once something is written under it. On a fresh store the
     * console therefore listed none, and an operator signing in for the first time was sent to the instances screen
     * to create the one tenant the deployment already serves, before any other screen would open. Creating it here
     * makes a fresh deployment's console usable on first sign-in. A deployment of several tenants names its own,
     * and a read-only one writes nothing, so both are left alone.
     *
     * <p>The one repository it serves is created beside it, for the same reason: it exists whether or not anything
     * was published into it, and writing its creation marker is what lists it on the Repositories screen from the
     * first sign-in rather than from the first publish.
     */
    @Bean
    public FixedTenant fixedTenant(TenantService tenants, Tenancy tenancy, ArtifactStore repositoryStore,
                                   ConfigurableEnvironment environment) {
        if (!tenancy.fixed() || environment.getProperty("jenreg.read-only", Boolean.class, false)) {
            return new FixedTenant(null);
        }
        String tenant = environment.getProperty("jenreg.default-tenant", "default");
        try {
            if (!tenants.exists(tenant)) {
                tenants.create(tenant);
            }
            String repository = environment.getProperty("jenreg.default-repository",
                    new RepositoryProperties().getDefaultRepository());
            ArtifactStore served = repositoryStore.scope(tenant).scope(repository);
            boolean[] held = {false};
            served.page("", "", 1, _ -> held[0] = true);
            if (!held[0]) {
                served.write(Scopes.CREATED, new ByteArrayInputStream(
                        Instant.now().toString().getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IllegalArgumentException raced) {
            // Another node created it between the check and the write - which is the outcome wanted.
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(DomainConfig.class).warn("The deployment's tenant '{}' or its repository could "
                    + "not be created; the console lists them once anything is written under them", tenant, e);
        }
        return new FixedTenant(tenant);
    }

    /** The tenant a fixed deployment serves, or {@code null} where the deployment names its own tenants. */
    public record FixedTenant(String name) {
    }

    /** How the deployment routes tenants, read once for the console: the fixed-tenant creation above, the landing
     *  that decides whether a tenant is chosen for the reader, and the header that shows which one is. */
    @Bean
    public Tenancy tenancy(ConfigurableEnvironment environment) {
        return new Tenancy(environment.getProperty("jenreg.tenancy", "fixed").equals("fixed"));
    }

    /** Whether the deployment serves one tenant ({@code jenreg.tenancy=fixed}) or several. */
    public record Tenancy(boolean fixed) {

        public boolean multi() {
            return !fixed;
        }
    }

    /** The deployment-wide operator tenant (operator-tenant, else default-tenant, else {@code default}): the scope
     *  where cross-tenant privileged mutations that belong to no single tenant are audited. Shared by the tenant purge
     *  and the volume reclaim so the two apply one rule. */
    private static String operatorTenant(ConfigurableEnvironment environment) {
        String operatorTenant = environment.getProperty("jenreg.operator-tenant", "");
        return operatorTenant.isBlank()
                ? environment.getProperty("jenreg.default-tenant", "default")
                : operatorTenant;
    }

    @Bean
    public SettingsAdmin settingsAdmin(ArtifactStore repositoryStore, ConfigurableEnvironment environment,
                                       TenantService tenantService, AuditTrail audit, CurrentTenant currentTenant,
                                       ConsoleActor actor, ObjectProvider<RepositoryRouting> routing) {
        // The console shares the store with the repository and reads the settings directly. The pin state, however,
        // comes from the operator's launch configuration (env vars, -D, the command line, external config files),
        // which the console's own environment carries too (identically in the recommended combined deployment). The
        // console mirrors the server's precedence rule here rather than depending on the repository-server
        // module that holds PinnedSettings - the decoupling under which it reads the settings catalogue via the SPI.
        // The tenant directory feeds the modules screen's orphaned-data diagnostic (a per-tenant scan, read-only).
        SettingsPins pins = new SettingsPins(environment);
        // The upstream-credential source reads its deploy-time bootstrap keys - notably secrets-key
        // (JENREG_SECRETS_KEY), the master key that envelope-encrypts a stored credential at rest - from the
        // console's own environment, exactly as the /api ConfigController path does, so a credential set through the
        // console is encrypted under the same key (and refused the same way when none is configured, §9).
        // Whether any URL reaches a repository name is the routing's own property, so the console asks the routing
        // rather than reading jenreg.tenancy itself - and asks it through an ObjectProvider because the console also
        // runs as its own development entry point, over a graph that installs no routing at all. Absent, it says
        // nothing: a console that cannot see the routing must not warn on a guess.
        RepositoryRouting installed = routing.getIfAvailable();
        return new SettingsAdmin(repositoryStore, pins::pinned, tenantService::all, audit, currentTenant, actor,
                Features.namespaced(environment::getProperty),
                name -> installed == null || installed.addresses(name)
                        ? null
                        : RepositoryRouting.unaddressableWarning(name));
    }

    @Bean
    public RepositoryAdmin repositoryAdmin(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                           ObservationRegistry observations) {
        return new RepositoryAdmin(repositoryStore, currentTenant, observations);
    }

    /** The format family's mark lookup, shared by every console surface that draws a format's mark. Format discovery
     *  is static for the life of the JVM and a mark is a constant in its format's module, so the lookup is resolved
     *  once here and memoized inside rather than rebuilt per render. */
    @Bean
    public FormatMarks formatMarks() {
        return FormatMarks.installed();
    }

    @Bean
    public RepositoryBrowse repositoryBrowse(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                             ObservationRegistry observations) {
        return new RepositoryBrowse(repositoryStore, currentTenant, observations);
    }


    @Bean
    public TenantLimits tenantLimits(ArtifactStore repositoryStore, Authorization authorization,
                                     CurrentTenant currentTenant, ObservationRegistry observations,
                                     AuditTrail audit, ConsoleActor actor) {
        return new TenantLimits(repositoryStore, authorization, currentTenant, observations, audit, actor);
    }

    @Bean
    public RepositoryImports repositoryImports(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                               ObservationRegistry observations, AuditTrail audit, ConsoleActor actor) {
        return new RepositoryImports(repositoryStore, currentTenant, observations, audit, actor);
    }

    @Bean
    public RepositoryLifecycle repositoryLifecycle(ArtifactStore repositoryStore, CurrentTenant currentTenant,
                                                   ObservationRegistry observations, AuditTrail audit,
                                                   ConsoleActor actor) {
        return new RepositoryLifecycle(repositoryStore, currentTenant, observations, audit, actor);
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
     * Where this console reads posture from: the deployment's stored settings layered over its environment, for the
     * tenant being asked about.
     *
     * <p>The base console's screen and header badge both read this seam, so contributing it here is the whole of what
     * makes them show this deployment's effective configuration rather than the process environment alone - and
     * there is one implementation of the reading rather than a second screen that happens to agree.
     */
    @Bean
    public PostureSource postureSource(SettingsAdmin settings, ConfigurableEnvironment environment) {
        return tenant -> {
            SettingsAdmin.CollectedPosture collected = settings.posture(tenant, environment::getProperty);
            return new PostureSource.Collected(collected.posture(), collected.collectedAt());
        };
    }

    /**
     * This console's catalogue: the module graph decorated with each implementation's stored, effective state - is
     * its module installed, is its gate open, which key opens it, what settings it contributes.
     *
     * <p>It is the same screen the base console serves, contributed rather than re-implemented. There were two
     * pages: this one, and a thinner one over the module graph alone. They rendered the same enumeration with
     * different markup, and only this one could say whether anything was switched on.
     */
    @Bean
    public SpiCatalogSource spiCatalogSource(SettingsAdmin settings) {
        return settings::catalog;
    }
}
