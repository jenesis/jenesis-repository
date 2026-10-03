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
 * store-backed {@link Settings}, the token-exchange and audit-trail plugins, the
 * settings-precedence probe, the tenant directory, the {@link Repositories} tenant kernel and the storage-namespace
 * registration. The declaration that resolves the store applies the layers, and the quota and read-only wrappers
 * this class must not restate; the {@link Authorization} is the server's own, as every composition has it.
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
