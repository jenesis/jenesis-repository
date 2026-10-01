package build.jenesis.repository.application;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.audit.AuditTrailProvider;
import build.jenesis.repository.server.spi.AnonymousRights;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.TokenExchange;
import build.jenesis.repository.server.spi.TokenExchangeProvider;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.staging.StagingProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DocumentMemory;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.TenantsProvider;
import build.jenesis.repository.gateway.LiveDefinitions;
import build.jenesis.repository.server.ArtifactStoreDecorator;
import build.jenesis.repository.server.kernel.SettingsScopeMove;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * The store, settings and tenant kernel wiring: this composition's layers over the artifact store, the
 * {@link Authorization}, the store-backed {@link Settings}, the token-exchange and audit-trail plugins, the
 * settings-precedence probe, the tenant directory, the {@link Repositories} tenant kernel and the storage-namespace
 * registration. The declaration that resolves the store applies the layers, and the quota and read-only wrappers
 * this class must not restate.
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreConfig.class);
    /**
     * This composition's two layers over the resolved store, contributed rather than declared as a second
     * {@code artifactStore} bean.
     *
     * <p>A redeclared bean could drop one of the wrappers around the resolution - the {@code jenrepo.quota} cap, say -
     * with nothing failing. The meter sits closest to the backend and the node's memories above it, so a read a memory
     * answers is not metered; read-only and quota stay outermost.
     */
    @Bean
    @Order(10)
    public ArtifactStoreDecorator meteringStoreDecorator(RepositoryProperties properties,
                                                         ObjectProvider<MeterRegistry> meterRegistry) {
        return store -> new MeteringArtifactStore(store, meterRegistry.getIfAvailable(), properties.getStore(),
                properties.isStoreFamilies());
    }

    @Bean
    @Order(20)
    public ArtifactStoreDecorator nodeMemoStoreDecorator() {
        return store -> NodeMemoStore.over(store, MissMemory.node(), DocumentMemory.node());
    }

    /** What this node resolved, said once at boot. */
    @Bean
    public ApplicationRunner storeBanner(RepositoryProperties properties) {
        return arguments -> LOGGER.info("jenesis-repository ready (storage {}, auth {}, vulnerability {})",
                properties.getStore(),
                properties.isAuth() ? "enforced" : "anonymous",
                properties.getVulnerabilityThreshold());
    }

    @Bean
    public Authorization authorization(RepositoryProperties properties, ArtifactStore store) throws IOException {
        // Opt-in; empty grants no anonymous access at all.
        String anonymousRights = properties.getAnonymousRights().strip();
        if (!properties.isAuth()) {
            // An explicit opt-out is legitimate, so it warns rather than failing the boot.
            LOGGER.warn("SECURITY: per-credential authorization is DISABLED (jenrepo.auth=false) - the "
                    + "repository is running ANONYMOUS/OPEN and every request is served without a credential. This is "
                    + "an explicit opt-out; unset it or set jenrepo.auth=true (the default) to enforce "
                    + "authorization.");
            // Under auth=false every request is already anonymous, so the grant is redundant and ignored.
            if (!anonymousRights.isEmpty()) {
                LOGGER.warn("SECURITY: jenrepo.anonymous-rights is set but jenrepo.auth=false, so "
                        + "the deployment is ALREADY fully open (every request is served anonymously) and the "
                        + "anonymous-rights grant is redundant and ignored. Set jenrepo.auth=true to make it "
                        + "meaningful: keys are then required and a keyless caller is limited to exactly this grant.");
            }
            return Authorization.anonymous();
        }
        // Names exactly what a keyless caller may do, louder for write or admin; the posture advisories carry it onto
        // the console and /api/posture.
        if (!anonymousRights.isEmpty()) {
            if (AnonymousRights.grantsWriteOrAdmin(anonymousRights)) {
                LOGGER.warn("SECURITY: anonymous access ENABLED with WRITE/ADMIN rights: {}. A keyless caller may "
                        + "mutate or administer artifacts with NO credential (a public drop-box / open admin) - the "
                        + "loudest anonymous combination. This is an explicit opt-in; unset "
                        + "jenrepo.anonymous-rights to require a key for every request.", anonymousRights);
            } else {
                LOGGER.warn("SECURITY: anonymous access ENABLED: {}. A keyless caller is granted these rights with no "
                        + "credential (the public-mirror pattern - pair with jenrepo.read-only=true for a "
                        + "browsable-but-immutable mirror). This is an explicit opt-in; unset "
                        + "jenrepo.anonymous-rights to require a key for every request.", anonymousRights);
            }
        }
        // A keyless request is decided against the anonymous grants by the same authorize call as any other.
        Authorization authorization = Authorization.enforcing(store)
                .withLifetimes(properties.getCredentialDefaultLifetime(), properties.getCredentialMaxLifetime())
                .withAnonymousRights(anonymousRights);
        // Every route that mints a credential requires one, so jenrepo.bootstrap-key provisions the first - the
        // contract of the server's own authorization bean, which this one replaces.
        String tenant;
        try {
            tenant = authorization.bootstrap(properties.getBootstrapKey());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(malformed.getMessage(), malformed);
        }
        if (tenant != null) {
            LOGGER.warn("SECURITY: a bootstrap key is provisioned for tenant '{}' (jenrepo.bootstrap-key) - it grants "
                    + "EVERY right on every repository of that tenant and never expires. Use it to issue the "
                    + "credentials you actually want, then unset it; it is re-provisioned on every boot for as long "
                    + "as it is set.", tenant);
        }
        // A process that died mid-derivation is one starting now, so boot repairs what it left behind.
        authorization.groups().repairDerivedGrants();
        return authorization;
    }

    @Bean
    public Settings settings(ArtifactStore store, Environment environment) throws IOException {
        // The key that encrypts SECRET settings at rest is deploy-time configuration (JENREPO_SECRETS_KEY); a
        // malformed value fails the boot naming the variable.
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        return new Settings(store, SecretCipher.of(config.apply("secrets-key")));
    }

    /**
     * The one-time move of the configuration kept outside the settings catalogue into its tenant, repository and
     * project settings ({@link SettingsScopeMove}), run before this node serves, since what it moves decides how a
     * request is answered. A read-only node moves nothing; a writing node of the deployment does.
     */
    @Bean
    public SettingsScopeMove.Moved settingsScopeMove(ArtifactStore store, Settings settings,
                                                    RepositoryProperties properties) throws IOException {
        if (properties.isReadOnly()) {
            return new SettingsScopeMove.Moved(new TreeMap<>());
        }
        SettingsScopeMove.Moved moved = new SettingsScopeMove(store,
                repository -> new StoreRepositoryInventory(repository).formerRetention()).run();
        // Anything this node read before the move is read again.
        settings.refresh();
        return moved;
    }

    @Bean
    public TokenExchange tokenExchange(Authorization authorization, Environment environment) {
        // NONE when no provider is installed, and /api/token answers 501.
        return TokenExchangeProvider.resolve(authorization,
                Features.namespaced(environment::getProperty));
    }

    @Bean
    public AuditTrail auditTrail(RepositoryProperties properties, ArtifactStore store, Environment environment) {
        // Off without auth, which has no actors to record, and in read-only mode, which has no mutations.
        return AuditTrailProvider.resolve(store, key -> "audit".equals(key) && (!properties.isAuth() || properties.isReadOnly())
                ? "false"
                : environment.getProperty(Features.key(key)));
    }

    @Bean
    public PinnedSettings pinnedSettings(ConfigurableEnvironment environment) {
        // Whether a key is fixed above the store (an env var, -D, an external config file), so it ignores the store.
        return new PinnedSettings(environment);
    }

    @Bean
    public Repositories repositories(ArtifactStore store, Authorization authorization, LiveConfig liveConfig,
                                     LiveDefinitions definitions, Environment environment) {
        // Staging and retention answer 501 when no provider is installed.
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        return new Repositories(store, authorization, liveConfig,
                StagingProvider.resolve(config), RetentionProvider.resolve(config), definitions);
    }

    @Bean
    public StorageNamespaces storageNamespaces(ArtifactStore store, RepositoryProperties properties) throws IOException {
        // Each installed module's manifest is persisted additively, so it outlives the module and the orphan
        // diagnostic and purge can name its leftover data. A read-only node, which runs neither, skips the write.
        StorageNamespaces namespaces = new StorageNamespaces(store);
        if (!properties.isReadOnly()) {
            namespaces.register();
        }
        return namespaces;
    }

    @Bean
    public Tenants tenants(ArtifactStore store, LiveConfig liveConfig, Environment environment) {
        return TenantsProvider.resolve(store, environment::getProperty, liveConfig.defaultTenant());
    }
}
