package build.jenesis.repository.cache.server;

import module java.base;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import build.jenesis.repository.observation.ObservabilityReport;

/**
 * The cache node's observability report, at the repository node's path and in its shape, so the cache's store cost is
 * read the same way. Gated by a cache credential as a lookup is, with a placeholder entry, so an unauthenticated caller
 * is challenged and a forged key learns nothing.
 *
 * <p>Only for the standalone cache node: a composing launcher answers the path for the whole process, and its scan
 * leaves this class out ({@link CacheNode}).
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
        // This context's report: the discovered sources and every source among its singletons.
        return ObservabilityReport.of(Arrays.stream(beans.getSingletonNames()).map(beans::getSingleton).filter(Objects::nonNull).toList()).view();
    }
}
