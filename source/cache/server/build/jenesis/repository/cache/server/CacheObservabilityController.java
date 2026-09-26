package build.jenesis.repository.cache.server;

import module java.base;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import build.jenesis.repository.observation.ObservabilityReport;

/**
 * The cache node's observability report, at the path and in the shape the repository node answers it - the core
 * report's one rendering - so what a cache costs the store is read the way what a repository costs it is. Gated by
 * a cache credential exactly as a lookup is: the same resolution the cache routes make, with a placeholder entry, so
 * an unauthenticated caller is challenged and a forged key learns nothing.
 *
 * <p>The standalone cache node's only: a composing launcher - the bundle carries both nodes over one store -
 * answers the same path for the whole process through the repository's endpoint, and its scan of this package
 * leaves this class out ({@link CacheNode}), which is decided by the composition rather than by a condition that
 * would have to guess what another module's scan registered.
 */
@RestController
public class CacheObservabilityController {

    private final Cache cache;
    private final ConfigurableListableBeanFactory beans;

    public CacheObservabilityController(Cache cache, ConfigurableListableBeanFactory beans) {
        this.cache = cache;
        this.beans = beans;
    }

    @GetMapping("/api/admin/observability")
    public ObservabilityReport.View observability(
            @RequestHeader(value = CacheController.PROJECT, required = false) String project,
            @RequestHeader(value = CacheController.KEY, required = false) String key,
            HttpServletResponse response) {
        if (cache.resolve(project, key, "0", "0", false) instanceof Cache.Rejected rejected) {
            response.setStatus(rejected.status());
            if (rejected.status() == 401) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Cache\"");
            }
            return null;
        }
        // This context's report: the discovered sources and every source among the singletons it has built.
        return ObservabilityReport.of(Arrays.stream(beans.getSingletonNames()).map(beans::getSingleton).filter(Objects::nonNull).toList()).view();
    }
}
