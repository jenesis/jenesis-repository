package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.store.CacheService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Build-cache project management over the API: the key-header-authenticated twin of the console's project and
 * eviction screens, reaching the same {@link CacheService} they do.
 *
 * <p><b>Why it exists.</b> Creating a cache project, pointing a build at it and forcing a sweep were reachable only
 * through a browser - the surface-parity rule reported the eight console routes behind them as sharing no
 * implementation with any API route, and it was right: there was no API route to share one with. A capability an
 * operator can only reach by clicking cannot be scripted, cannot run from CI, and cannot be driven by the CLI,
 * which is the whole of what "one capability, three surfaces" forbids.
 *
 * <p><b>How parity is kept rather than claimed.</b> Every handler here calls {@link CacheService}, which is what the
 * console's {@code ProjectsController} and {@code EvictionController} call. That is the thing the parity rule
 * measures - two surfaces are the same capability when they reach the same code - so this closes the gap instead of
 * adding a second implementation that would drift from the first.
 *
 * <p><b>Why the service is built per request.</b> {@link CacheService} takes the tenant and the acting identity as
 * two one-method seams, because who is acting is a property of the calling surface: the console names its
 * signed-in member, and this names the presented key's hash, exactly as the sibling API controllers record an
 * actor. Nothing is lost by constructing it per request - it holds references and no state, and the "a pass is
 * already running" guard it enforces lives in a stored marker with a stale-pass timeout, not in the instance, so
 * two surfaces and two nodes see one answer.
 *
 * <p><b>What an eviction call does and does not do.</b> It starts a pass and returns; the sweep walks the project's
 * entries off the request path, which is what keeps this bounded on a cache holding millions of entries. So the
 * answer is {@code started}, not a result - {@code false} means a pass was already running, never that anything
 * failed - and the outcome is read back from the project's stats.
 */
@RestController
public class CacheProjectsController {

    private final ObjectProvider<CacheStorage> storage;
    private final AuditTrail audit;
    private final Repositories repositories;
    private final CacheService.Passes passes;

    /**
     * The cache's own segment of the store is wired by the console node, so a repository-only composition does not
     * have it - and asking for it outright would stop that composition booting rather than leaving this endpoint
     * out of it. It is resolved lazily for the same reason {@code StagingController} answers 501 rather than
     * failing to construct: an absent feature is a 501, never a dead context.
     *
     * <p>It is the <b>root</b> storage, scoped per request by the tenant the presented key names. The console's
     * {@code cacheTenantStorage} is request-scoped and takes its tenant from the selected session instead, which is
     * right for a screen and wrong here twice over: a headless call has no session, so it would throw rather than
     * answer, and a call that did carry one would act on the session's tenant rather than the key's.
     */
    public CacheProjectsController(@Qualifier("cacheRootStorage") ObjectProvider<CacheStorage> storage,
                                   AuditTrail audit,
                                   Repositories repositories) {
        this(storage, audit, repositories, CacheService.Passes.BACKGROUND);
    }

    /**
     * With how an eviction's pass is started. The composition takes {@link CacheService.Passes#BACKGROUND}, the
     * behaviour the class javadoc describes; a caller that owns the directory the pass writes into - a unit test
     * over a temporary store - takes {@link CacheService.Passes#CALLING_THREAD}, so the pass is over before the
     * call returns and nothing deletes the tree under it.
     */
    public CacheProjectsController(ObjectProvider<CacheStorage> storage,
                                   AuditTrail audit,
                                   Repositories repositories,
                                   CacheService.Passes passes) {
        this.storage = storage;
        this.audit = audit;
        this.repositories = repositories;
        this.passes = passes;
    }

    /** Every project on the volume with its stored counts and caps - the project set, never the entries behind it. */
    @GetMapping("/api/cache/projects")
    public List<CacheService.ProjectSummary> list(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                                  HttpServletResponse response) throws IOException {
        CacheService service = service(key, response);
        return service == null ? List.of() : service.listProjects();
    }

    @PostMapping("/api/cache/projects")
    public Map<String, Object> create(@RequestParam("name") String name,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      HttpServletResponse response) throws IOException {
        CacheService service = service(key, response);
        if (service == null) {
            return Map.of();
        }
        RepositoryRequests.rejectTraversal(name);
        service.createProject(name);
        response.setStatus(201);
        return Map.of("name", name, "created", true);
    }

    @GetMapping("/api/cache/projects/{name}")
    public CacheService.ProjectDetail detail(@PathVariable("name") String name,
                                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                                             HttpServletResponse response) throws IOException {
        CacheService service = service(key, response);
        if (service == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(name);
        return service.project(name);
    }

    /** The well-known cache values; an omitted parameter clears that value rather than leaving the previous one. */
    @PostMapping("/api/cache/projects/{name}/cache")
    public CacheService.ProjectDetail saveCache(@PathVariable("name") String name,
                                                @RequestParam(name = "size", required = false) String size,
                                                @RequestParam(name = "lru", required = false) String lru,
                                                @RequestParam(name = "ttl", required = false) String ttl,
                                                @RequestHeader(value = Repositories.KEY, required = false) String key,
                                                HttpServletResponse response) throws IOException {
        CacheService service = service(key, response);
        if (service == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(name);
        service.saveCacheConfig(name, size, lru, ttl);
        return service.project(name);
    }

    @PostMapping("/api/cache/projects/{name}/evict/size")
    public Map<String, Object> enforceSizeCap(@PathVariable("name") String name,
                                              @RequestHeader(value = Repositories.KEY, required = false) String key,
                                              HttpServletResponse response) throws IOException {
        return pass(name, key, response, CacheService::enforceSizeCap);
    }

    @PostMapping("/api/cache/projects/{name}/evict/ttl")
    public Map<String, Object> expireTtl(@PathVariable("name") String name,
                                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                                         HttpServletResponse response) throws IOException {
        return pass(name, key, response, CacheService::expireTtl);
    }

    @PostMapping("/api/cache/projects/{name}/evict/clear")
    public Map<String, Object> clear(@PathVariable("name") String name,
                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                     HttpServletResponse response) throws IOException {
        return pass(name, key, response, CacheService::clearAll);
    }

    @PostMapping("/api/cache/projects/{name}/recount")
    public Map<String, Object> recount(@PathVariable("name") String name,
                                       @RequestHeader(value = Repositories.KEY, required = false) String key,
                                       HttpServletResponse response) throws IOException {
        return pass(name, key, response, CacheService::recount);
    }

    /** One shape for the four passes: they all start work and answer whether this call is the one that started it. */
    private Map<String, Object> pass(String name, String key, HttpServletResponse response, Pass pass)
            throws IOException {
        CacheService service = service(key, response);
        if (service == null) {
            return Map.of();
        }
        RepositoryRequests.rejectTraversal(name);
        boolean started = pass.run(service, name);
        return Map.of("project", name, "started", started, "stats", service.stats(name));
    }

    @FunctionalInterface
    private interface Pass {
        boolean run(CacheService service, String project) throws IOException;
    }

    /**
     * The tenant's cache service, or {@code null} with the status already set when the request names no usable
     * tenant. The actor is the presented key's hash, which is what the sibling controllers record and what keeps an
     * audit row attributable without a console session to read a member from.
     */
    private CacheService service(String key, HttpServletResponse response) {
        CacheStorage cache = storage.getIfAvailable();
        if (cache == null) {
            response.setStatus(501);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        String actor = key == null ? "anonymous" : Authorization.hash(key);
        return new CacheService(cache.scope(tenant), audit, () -> tenant, () -> actor, passes);
    }
}
