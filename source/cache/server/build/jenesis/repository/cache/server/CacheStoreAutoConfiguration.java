package build.jenesis.repository.cache.server;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoreBindings;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The store a cache running without the repository delegates into: the backend {@code jenrepo.store} selects,
 * metered as the repository node meters its own.
 *
 * <p>It is an auto-configuration ordered after the repository's so that it is only ever the fallback. A configuration
 * the composition imports is read before every auto-configuration, so a store declared beside the cache's other
 * beans would become the root of a node that also serves the repository, and the repository's store - with its
 * quota, read-only wrapper and node memories - would never be built. Ordered after it, the condition sees the
 * repository's store where there is one, and the cache takes its segment from that. It is no part of a node whose
 * build cache is switched off.
 */
@AutoConfiguration(afterName = "build.jenesis.repository.server.RepositoryAutoConfiguration")
@ConditionalOnProperty(name = "jenrepo." + CacheNode.GATE, havingValue = "true", matchIfMissing = true)
public class CacheStoreAutoConfiguration {

    /** The deployment's store, selected by {@code jenrepo.store}, carrying what the composition binds to it. */
    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(Environment environment, MeterRegistry registry,
                                       ObjectProvider<StoreBindings> bindings) {
        String backend = environment.getProperty("jenrepo.store");
        return new MeteringArtifactStore(StoreBindings.all(bindings.orderedStream())
                .over(ArtifactStoreProvider.resolve(backend, environment::getProperty)), registry, backend);
    }
}
