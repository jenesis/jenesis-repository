package build.jenesis.repository.cache.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * The build cache, as one thing an application carries or not: on one node it is beans beside the repository's, so a
 * deployment that serves artifacts and no cache needs a way to say so.
 *
 * <p>Read as the context starts, since it decides whether the cache's controller and its permit-all chain are
 * registered: the cache authorizes its own requests, so its routes are permitted by the order-1 chain, and that chain
 * without a controller would leave a permitted path space in front of nothing. Default on;
 * {@code jenrepo.build-cache=false} takes it out.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jenrepo." + CacheNode.GATE, havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "build.jenesis.repository.cache.server",
        // The cache's own entry point, which a composing launcher replaces; the cache node's own report endpoint,
        // since a composing launcher answers the same path for the whole process over the same store; and the
        // fallback store, an auto-configuration that a scan would read before the repository's store.
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "build\\.jenesis\\.repository\\.cache\\.server\\.(CacheServer|CacheObservabilityController|CacheStoreAutoConfiguration)"))
public class CacheNode {

    /** Whether this application serves the build cache. Read before the context starts; applies on restart. */
    public static final String GATE = "build-cache";
}
