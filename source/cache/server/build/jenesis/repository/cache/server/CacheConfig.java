package build.jenesis.repository.cache.server;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorage;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.KeyUsageTrackerProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Durations;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Builds the {@link Cache} from {@link CacheProperties} ({@code jenrepo.cache.*}) and starts the reaper; a trial
 * bootstrap key is logged with a warning. No storage backend is selected: the cache delegates into the repository's
 * store, which reads its own configuration from the same {@link Environment}.
 *
 * <p>The same store also backs the credential store: the per-credential grants under {@code auth/<tenant>/<hash>/},
 * and an enforcing {@link Authorization} over it decides {@code cache:read}/{@code cache:write}. The authorization is
 * {@link ConditionalOnMissingBean conditional}, and the store is {@link CacheStoreAutoConfiguration}'s fallback, so
 * when the cache is combined with a node that serves the artifact repository and the admin console it reads and
 * writes that node's store - quota, read-only wrapper and all - and one credential authorizes every surface.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public Authorization authorization(ArtifactStore artifactStore) {
        return Authorization.enforcing(artifactStore);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public KeyUsageTracker keyUsageTracker(Authorization authorization, Environment environment) {
        // Usage tracking is a discovered plugin; NONE when absent.
        return KeyUsageTrackerProvider.resolve(authorization,
                key -> environment.getProperty("jenrepo.cache." + key));
    }

    @Bean(destroyMethod = "stop")
    public Cache cache(CacheProperties properties, Authorization authorization, KeyUsageTracker keyUsageTracker,
                       Environment environment, MeterRegistry registry, ArtifactStore artifactStore) {
        Duration reaper = interval(properties.getReaper(), "jenrepo.cache.reaper");
        int minFreePercent = (int) Math.clamp(properties.getMinFreePercent(), 0, 100);
        String bootstrapKey = properties.getKey();
        String defaultTenant = properties.getDefaultTenant();
        if (bootstrapKey != null && !bootstrapKey.isBlank()) {
            LOGGER.warn("SECURITY: jenrepo.cache.key is set - a single static key is accepted for tenant '{}' with "
                    + "full read+write. This is intended for trials only; for any real deployment unset it and issue "
                    + "per-credential keys through an admin console instead.", defaultTenant);
        }
        Cache cache = new Cache(
                DelegatingCacheStorage.over(artifactStore), authorization,
                properties.getMaxBytes(), properties.getProjects(), reaper, properties.getMinFree(),
                minFreePercent, properties.getDefaultProject(), properties.isProjectRequired(),
                bootstrapKey, defaultTenant, registry);
        cache.usageTracker(keyUsageTracker);
        Duration touchInterval = interval(properties.getTouchInterval(), "jenrepo.cache.touch-interval");
        cache.touchInterval(touchInterval);
        // A project's policy is trusted for the same window as the credential a request is authorised against.
        Duration policyInterval = StoreCache.configuredTtl();
        cache.policyInterval(policyInterval);
        // A project's policy is its project settings, from the documents the console and API write.
        cache.policies((tenant, project) -> StoredSettings.projectChain(artifactStore, tenant, project));
        cache.start();
        LOGGER.info("jenesis-cache ready (storage {}, project cache {}, reaper {}, touch window {}, policy window {}, "
                        + "min free {}B/{}%, project {}, keys {})",
                // The cache reports the repository store it delegates to.
                environment.getProperty("jenrepo.store", "filesystem"), properties.getProjects(),
                reaper == null ? "off" : reaper, touchInterval == null ? "every hit" : touchInterval,
                policyInterval.isZero() ? "every request" : policyInterval,
                properties.getMinFree(), minFreePercent,
                properties.isProjectRequired() ? "header required" : "default " + properties.getDefaultProject(),
                bootstrapKey == null || bootstrapKey.isBlank()
                        ? "required"
                        : "required (+ trial bootstrap key -> tenant " + defaultTenant + ")");
        return cache;
    }

    /** A cache dial's interval, or {@code null} for none: unset, off, or not positive. */
    private static Duration interval(String value, String key) {
        Duration interval = Durations.dial(value, key, null);
        return interval == null || interval.isZero() || interval.isNegative() ? null : interval;
    }

}
