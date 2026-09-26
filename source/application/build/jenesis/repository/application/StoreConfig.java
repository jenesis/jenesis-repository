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
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * The store / settings / tenant kernel wiring split out of {@link RepositoryConfig}: the artifact store
 * (a backend chosen by name through {@code ArtifactStoreProvider}, wrapped for metering then read-only), the
 * {@link Authorization}, the store-backed {@link Settings}, the discovered token-exchange and audit-trail plugins,
 * the settings-precedence probe, the tenant directory and the {@link Repositories} tenant kernel, and the boot-time
 * storage-namespace registration. Every bean is copied verbatim from the former monolith; the split is
 * behaviour-preserving. The store's wrap order is not this class's business: it contributes layers through
 * {@link ArtifactStoreDecorator} and the declaration that resolves the store applies them, along with the quota
 * and read-only wrappers this class must not restate.
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreConfig.class);
    /**
     * This composition's two layers over the resolved store, contributed rather than declared as a second
     * {@code artifactStore} bean.
     *
     * <p>They used to arrive by redeclaring that bean, which meant restating the resolution and the wrappers
     * around it - and the restatement had dropped the deployment-wide {@code jenreg.quota} cap, so an operator
     * who set one on the image and swapped in this one lost it with nothing to read. A contribution cannot
     * drop what it does not contain.
     *
     * <p>Order is the point: the meter sits closest to the backend and the node's memories above it, so a read
     * the memory answers is a read the meter does not count - which is what "a read spared" means. Read-only and
     * quota stay outermost, applied by the declaration this layers into.
     */
    @Bean
    @Order(10)
    public ArtifactStoreDecorator meteringStoreDecorator(RepositoryProperties properties,
                                                         ObjectProvider<MeterRegistry> meterRegistry) {
        return store -> {
            MeteringArtifactStore.families(properties.isStoreFamilies());
            return new MeteringArtifactStore(store, meterRegistry.getIfAvailable(), properties.getStore());
        };
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
        // The strictly-opt-in anonymous role, read the same way this bean reads auth/read-only - off the
        // @ConfigurationProperties-bound RepositoryProperties (jenreg.anonymous-rights), not an ad-hoc
        // config.apply, as isAuth()/isReadOnly() do. Default empty ⇒ no anonymous
        // access whatsoever, byte-for-byte today's keyless rejection.
        String anonymousRights = properties.getAnonymousRights().strip();
        if (!properties.isAuth()) {
            // Secure-defaults principle: an insecure configuration must be loud, not silent. Per-credential
            // authorization is on by default; this deployment turned it off explicitly (jenreg.auth=false),
            // so warn at boot that every request is served with no credential. Anonymous is a legitimate explicit
            // choice, so this warns rather than failing the boot.
            LOGGER.warn("SECURITY: per-credential authorization is DISABLED (jenreg.auth=false) - the "
                    + "repository is running ANONYMOUS/OPEN and every request is served without a credential. This is "
                    + "an explicit opt-out; unset it or set jenreg.auth=true (the default) to enforce "
                    + "authorization.");
            // Guardrail: anonymous-rights is only meaningful under an enforcing deployment. Under auth=false the
            // instance is ALREADY fully open, so a configured anonymous-rights is redundant and ignored - warn so the
            // operator is not misled into thinking it is narrowing an open deployment (mirrors the autoconfig).
            if (!anonymousRights.isEmpty()) {
                LOGGER.warn("SECURITY: jenreg.anonymous-rights is set but jenreg.auth=false, so "
                        + "the deployment is ALREADY fully open (every request is served anonymously) and the "
                        + "anonymous-rights grant is redundant and ignored. Set jenreg.auth=true to make it "
                        + "meaningful: keys are then required and a keyless caller is limited to exactly this grant.");
            }
            return Authorization.anonymous();
        }
        // Second guardrail: a loud startup WARN naming exactly what a keyless caller may do, escalated for
        // write/admin - the mirror of the free RepositoryAutoConfiguration WARN (this bean wins over the free
        // @ConditionalOnMissingBean authorization bean, so the free WARN never fires here). The
        // jenreg.anonymous.* security-posture advisories (logged by the logSecurityPosture at boot, which runs in
        // this deployment) carry the governance escalation onto the console and GET /api/posture. Default (empty) ⇒ no
        // anonymous access and no warning, byte-for-byte today's behaviour.
        if (!anonymousRights.isEmpty()) {
            if (Authorization.grantsWriteOrAdmin(anonymousRights)) {
                LOGGER.warn("SECURITY: anonymous access ENABLED with WRITE/ADMIN rights: {}. A keyless caller may "
                        + "mutate or administer artifacts with NO credential (a public drop-box / open admin) - the "
                        + "loudest anonymous combination. This is an explicit opt-in; unset "
                        + "jenreg.anonymous-rights to require a key for every request.", anonymousRights);
            } else {
                LOGGER.warn("SECURITY: anonymous access ENABLED: {}. A keyless caller is granted these rights with no "
                        + "credential (the public-mirror pattern - pair with jenreg.read-only=true for a "
                        + "browsable-but-immutable mirror). This is an explicit opt-in; unset "
                        + "jenreg.anonymous-rights to require a key for every request.", anonymousRights);
            }
        }
        // The one choke-point: hand the anonymous grant set to the free Authorization the multi-tenant
        // the authorization manager already delegates every decision to (authorize(key, scope, path, required)),
        // so a keyless request is decided against anonymous-rights identically to the keyless branch - no second
        // code path. Empty grants ⇒ keyless UNAUTHORIZED, exactly as today.
        Authorization authorization = Authorization.enforcing(store)
                .withLifetimes(properties.getCredentialDefaultLifetime(), properties.getCredentialMaxLifetime())
                .withAnonymousRights(anonymousRights);
        // The first credential of an enforcing deployment: every route that could mint one requires one already, so
        // jenreg.bootstrap-key is provisioned here - the same contract the server's authorization bean carries,
        // which this bean replaces and therefore has to honour. An object-store deployment has no other route in.
        String tenant;
        try {
            tenant = authorization.bootstrap(properties.getBootstrapKey());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(malformed.getMessage(), malformed);
        }
        if (tenant != null) {
            LOGGER.warn("SECURITY: a bootstrap key is provisioned for tenant '{}' (jenreg.bootstrap-key) - it grants "
                    + "EVERY right on every repository of that tenant and never expires. Use it to issue the "
                    + "credentials you actually want, then unset it; it is re-provisioned on every boot for as long "
                    + "as it is set.", tenant);
        }
        // The other boot obligation an enforcing deployment's authorization carries, beside the bootstrap key:
        // repair what an interrupted group derivation left behind. Both editions honour it here, in the same
        // place and for the same reason - a process that died mid-derivation is a process that is starting now.
        authorization.repairDerivedGrants();
        return authorization;
    }

    @Bean
    public Settings settings(ArtifactStore store, Environment environment) throws IOException {
        // The master key(s) that envelope-encrypt SECRET settings at rest are deploy-time bootstrap infra, like the
        // store credentials themselves: read from the environment through the same
        // effective-config lookup the other env-only credentials use (github-token, the store-backend credentials), so
        // "secrets-key"
        // (the env var JENREG_SECRETS_KEY via Spring relaxed binding) is a first-class, allowlisted
        // bootstrap config read rather than a stranded key. A malformed value fails fast here (at boot), naming the
        // variable (§9).
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        return new Settings(store, SecretCipher.of(config.apply("secrets-key")));
    }

    @Bean
    public TokenExchange tokenExchange(Authorization authorization, Environment environment) {
        // The token exchange is a discovered plugin (the oidc module); NONE when absent - /api/token then
        // answers 501, and the server carries no OAuth2/JOSE stack.
        return TokenExchangeProvider.resolve(authorization,
                Features.namespaced(environment::getProperty));
    }

    @Bean
    public AuditTrail auditTrail(RepositoryProperties properties, ArtifactStore store, Environment environment) {
        // The audit trail is a discovered plugin; recording additionally requires auth, since an anonymous
        // deployment has no actors worth recording, and is off in read-only mode, where the trail is a store write
        // and there is no mutation to record.
        return AuditTrailProvider.resolve(store, key -> "audit".equals(key) && (!properties.isAuth() || properties.isReadOnly())
                ? "false"
                : environment.getProperty(Features.key(key)));
    }

    @Bean
    public PinnedSettings pinnedSettings(ConfigurableEnvironment environment) {
        // The one origin probe for the settings-precedence rule: whether a key is fixed by a source above the store
        // (an env var, -D, an external config file). LiveConfig and the settings screen consult it so a pinned key
        // ignores the store.
        return new PinnedSettings(environment);
    }

    @Bean
    public Repositories repositories(ArtifactStore store, Authorization authorization, LiveConfig liveConfig,
                                     LiveDefinitions definitions, Environment environment) {
        // Staging and the retention engine are discovered plugins; with no provider module installed the
        // corresponding endpoints answer 501.
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        return new Repositories(store, authorization, liveConfig,
                StagingProvider.resolve(config), RetentionProvider.resolve(config), definitions);
    }

    @Bean
    public StorageNamespaces storageNamespaces(ArtifactStore store, RepositoryProperties properties) throws IOException {
        // Boot persists every installed module's storage manifest (an additive, idempotent write - never a
        // deletion), so the manifest outlives a later module removal: that is what lets the orphan diagnostic
        // name leftover data and the operator purge a key-space whose declaring module is gone. A read-only
        // deployment runs no store write, so it skips registration (which would raise ReadOnlyException at boot):
        // the manifest it would persist is only consulted by the orphan diagnostic and the operator purge, neither
        // of which a read-only instance ever performs.
        StorageNamespaces namespaces = new StorageNamespaces(store);
        if (!properties.isReadOnly()) {
            namespaces.register();
        }
        return namespaces;
    }

    @Bean
    public Tenants tenants(ArtifactStore store, LiveConfig liveConfig, Environment environment) {
        // The tenant directory is a discovered plugin; a store-backed module answers, so the
        // directory reflects the shared <tenant>/<repository> layout and can grow.
        return TenantsProvider.resolve(store, environment::getProperty, liveConfig.defaultTenant());
    }
}
