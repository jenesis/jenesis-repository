package build.jenesis.repository.cache.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * The build cache, as one thing an application either carries or does not.
 *
 * <p>Same reason as the console's gate, and the same shape: the cache used to be its own image, so "do not run it"
 * was how a deployment without a build cache was expressed. On one node it is beans beside the repository's, and a
 * deployment that serves artifacts and wants no cache endpoint needs a way to say so.
 *
 * <p>Read as the context starts rather than from the settings store, because it decides whether the cache's
 * controller and its permit-all chain are registered at all. That chain is the reason it matters more than a
 * feature flag would: the cache does its own per-request key authorization, so its routes are permitted by the
 * order-1 chain, and registering that chain for a cache nobody serves would leave a permitted path space in front
 * of a controller that is not there.
 *
 * <p>Default on: an application carrying the cache module meant to serve it. {@code jenreg.build-cache=false}
 * takes it out.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jenreg." + CacheNode.GATE, havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "build.jenesis.repository.cache.server",
        // The cache's own entry point, which a composing launcher replaces - and the cache node's own report
        // endpoint, since a composing launcher answers the same path for the whole process over the same store.
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "build\\.jenesis\\.repository\\.cache\\.server\\.(CacheServer|CacheObservabilityController)"))
public class CacheNode {

    /** Whether this application serves the build cache at all. Read before the context starts; applies on restart. */
    public static final String GATE = "build-cache";
}
