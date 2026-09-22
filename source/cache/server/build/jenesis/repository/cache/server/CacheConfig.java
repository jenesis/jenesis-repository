package build.jenesis.repository.cache.server;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.cache.storage.CacheStorageProvider;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.KeyUsageTrackerProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Durations;
import io.micrometer.core.instrument.MeterRegistry;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Builds the {@link Cache} from {@link CacheProperties} (bound from {@code jenreg.cache.*} / the
 * matching {@code JENREG_CACHE_*} environment variables) and starting the reaper. A configured trial
 * bootstrap key is logged with a strong warning. There is no storage backend to select here: the cache
 * delegates into the repository's store, which reads its own configuration (root / bucket / connection
 * string) from the same {@link Environment}, so every app shares one config surface.
 *
 * <p>The same backend also backs the credential store: an {@link ArtifactStore} rooted at the cache's
 * own storage root holds the per-credential grants under {@code auth/<tenant>/<hash>/}, and an enforcing
 * {@link Authorization} over it decides {@code cache:read}/{@code cache:write}. Both beans are
 * {@link ConditionalOnMissingBean conditional}, so when this configuration is combined with another app
 * that already contributes an {@link ArtifactStore} and {@link Authorization} (a single-node deployment
 * that also serves the artifact repository and the admin console), the cache reads and writes the very
 * same credential store as those surfaces and one credential authorizes them all.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheConfig.class);

    /** The repository's store, selected by {@code jenreg.store}. There is one store per deployment and the cache
     *  delegates into a segment of it, so there is no second backend selection to disagree with this one. */
    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(Environment environment, MeterRegistry registry) {
        // Metered exactly as the repository node meters its store: the cache delegates into a segment of the same
        // store, and the operation counts are what its soak and the walks screen read.
        String backend = environment.getProperty("jenreg.store");
        return new MeteringArtifactStore(ArtifactStoreProvider.resolve(backend, environment::getProperty),
                registry, backend);
    }

    @Bean
    @ConditionalOnMissingBean
    public Authorization authorization(ArtifactStore artifactStore) {
        return Authorization.enforcing(artifactStore);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public KeyUsageTracker keyUsageTracker(Authorization authorization, Environment environment) {
        // Usage tracking is a discovered plugin (the usage module); NONE when absent.
        return KeyUsageTrackerProvider.resolve(authorization,
                key -> environment.getProperty("jenreg.cache." + key));
    }

    @Bean(destroyMethod = "stop")
    public Cache cache(CacheProperties properties, Authorization authorization, KeyUsageTracker keyUsageTracker,
                       Environment environment, MeterRegistry registry, ArtifactStore artifactStore) {
        Duration reaper = interval(properties.getReaper());
        int minFreePercent = (int) Math.clamp(properties.getMinFreePercent(), 0, 100);
        String bootstrapKey = properties.getKey();
        String defaultTenant = properties.getDefaultTenant();
        if (bootstrapKey != null && !bootstrapKey.isBlank()) {
            LOGGER.warn("SECURITY: jenreg.cache.key is set - a single static key is accepted for tenant '{}' with "
                    + "full read+write. This is intended for trials only; for any real deployment unset it and issue "
                    + "per-credential keys through an admin console instead.", defaultTenant);
        }
        Cache cache = new Cache(
                CacheStorageProvider.resolve(environment::getProperty, artifactStore), authorization,
                properties.getMaxBytes(), properties.getProjects(), reaper, properties.getMinFree(),
                minFreePercent, properties.getDefaultProject(), properties.isProjectRequired(),
                bootstrapKey, defaultTenant, registry);
        cache.usageTracker(keyUsageTracker);
        Duration touchInterval = interval(properties.getTouchInterval());
        cache.touchInterval(touchInterval);
        // A project's policy is trusted for the same window as the credential a request is authorised against.
        Duration policyInterval = StoreCache.configuredTtl();
        cache.policyInterval(policyInterval);
        cache.start();
        LOGGER.info("jenesis-cache ready (storage {}, project cache {}, reaper {}, touch window {}, policy window {}, "
                        + "min free {}B/{}%, project {}, keys {})",
                // The cache has no backend of its own: it reports the repository store it delegates to.
                environment.getProperty("jenreg.store", "filesystem"), properties.getProjects(),
                reaper == null ? "off" : reaper, touchInterval == null ? "every hit" : touchInterval,
                policyInterval.isZero() ? "every request" : policyInterval,
                properties.getMinFree(), minFreePercent,
                properties.isProjectRequired() ? "header required" : "default " + properties.getDefaultProject(),
                bootstrapKey == null || bootstrapKey.isBlank()
                        ? "required"
                        : "required (+ trial bootstrap key -> tenant " + defaultTenant + ")");
        return cache;
    }

    private static Duration interval(String value) {
        if (value == null || value.isBlank() || value.equals("0") || value.equalsIgnoreCase("off")) {
            return null;
        }
        Duration duration = Durations.parse(value);
        return duration.isZero() || duration.isNegative() ? null : duration;
    }

}
