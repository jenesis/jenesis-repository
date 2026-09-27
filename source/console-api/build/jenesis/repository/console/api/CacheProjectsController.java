package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.SettingsAdmin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final RepositoryRouting routing;
    private final CacheService.Passes passes;
    private final ArtifactStore root;

    /**
     * The cache's own segment of the store is wired by the console node, so a repository-only composition does not
     * have it - and asking for it outright would stop that composition booting rather than leaving this endpoint
     * out of it. It is resolved lazily for the same reason {@code StagingController} answers 501 rather than
     * failing to construct: an absent feature is a 501, never a dead context.
     *
     * <p>It is the <b>root</b> storage, scoped per request by the tenant the routing answers for the request. The
     * console's {@code cacheTenantStorage} is request-scoped and takes its tenant from the selected session instead,
     * which is right for a screen and wrong here twice over: a headless call has no session, so it would throw rather
     * than answer, and a call that did carry one would act on the session's tenant rather than the request's.
     */
    public CacheProjectsController(@Qualifier("cacheRootStorage") ObjectProvider<CacheStorage> storage,
                                   AuditTrail audit,
                                   RepositoryRouting routing, ArtifactStore root) {
        this(storage, audit, routing, root, CacheService.Passes.BACKGROUND);
    }

    /**
     * With how an eviction's pass is started. The composition takes {@link CacheService.Passes#BACKGROUND}, the
     * behaviour the class javadoc describes; a caller that owns the directory the pass writes into - a unit test
     * over a temporary store - takes {@link CacheService.Passes#CALLING_THREAD}, so the pass is over before the
     * call returns and nothing deletes the tree under it.
     */
    public CacheProjectsController(ObjectProvider<CacheStorage> storage,
                                   AuditTrail audit,
                                   RepositoryRouting routing, ArtifactStore root,
                                   CacheService.Passes passes) {
        this.storage = storage;
        this.audit = audit;
        this.routing = routing;
        this.root = root;
        this.passes = passes;
    }

    /**
     * Every project on the volume with its stored counts and caps - the project set, never the entries behind it.
     *
     * <p>It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @GetMapping("/api/cache/projects")
    public List<CacheService.ProjectSummary> list(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        return service == null ? List.of() : service.listProjects();
    }

    /**
     * Create a project - {@code POST /api/cache/projects?name=<project>}, with an optional
     * {@code {"settings":{...}}} body creating it with those as its own settings in one step: every value validated
     * through the catalogue first, {@code 400} naming every refusal with nothing written, the settings stored before
     * the project exists ({@link CacheService#createProject(String, Map)}). {@code 400} too for a project that exists.
     *
     * <p>Validating reads the deployment's settings documents, one object per module under a constant prefix, narrow
     * by construction.
     */
    @PostMapping("/api/cache/projects")
    public Map<String, Object> create(@RequestParam("name") String name,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestBody(required = false) ProjectRequest body,
                                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        if (service == null) {
            return Map.of();
        }
        RepositoryRequests.rejectTraversal(name);
        service.createProject(name, body == null || body.settings() == null ? Map.of() : body.settings());
        response.setStatus(201);
        return Map.of("name", name, "created", true);
    }

    /**
     * It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @GetMapping("/api/cache/projects/{name}")
    public CacheService.ProjectDetail detail(@PathVariable("name") String name,
                                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                                             HttpServletRequest request,
                                             HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        if (service == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(name);
        return service.project(name);
    }

    /**
     * The well-known cache values; an omitted parameter clears that value rather than leaving the previous one.
     *
     * <p>It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @PostMapping("/api/cache/projects/{name}/cache")
    public CacheService.ProjectDetail saveCache(@PathVariable("name") String name,
                                                @RequestParam(name = "size", required = false) String size,
                                                @RequestParam(name = "lru", required = false) String lru,
                                                @RequestParam(name = "ttl", required = false) String ttl,
                                                @RequestHeader(value = Repositories.KEY, required = false) String key,
                                                HttpServletRequest request,
                                                HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        if (service == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(name);
        service.saveCacheConfig(name, size, lru, ttl);
        return service.project(name);
    }

    /** A project's settings - every project setting the catalogue carries, with the project's effective value, what it
     *  would inherit from its tenant and the deployment, and whether it set its own.
     *
     * <p>It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @GetMapping("/api/cache/projects/{name}/settings")
    public List<SettingView> settings(@PathVariable("name") String name,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        if (service == null) {
            return List.of();
        }
        RepositoryRequests.rejectTraversal(name);
        return service.settings(name).stream().flatMap(group -> group.settings().stream()).map(SettingView::of)
                .toList();
    }

    /**
     * Set one project setting, validated through the catalogue: {@code 400} naming the refusal, nothing stored.
     *
     * <p>It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @PutMapping("/api/cache/projects/{name}/settings/{setting}")
    public void setSetting(@PathVariable("name") String name, @PathVariable("setting") String setting,
                           @RequestBody(required = false) Map<String, String> body,
                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                           HttpServletRequest request, HttpServletResponse response) throws IOException {
        String value = body == null || body.get("value") == null ? "" : body.get("value");
        writeSetting(name, setting, value, key, request, response);
    }

    /**
     * Clear one project setting, so the project inherits its tenant's and the deployment's again.
     *
     * <p>It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @DeleteMapping("/api/cache/projects/{name}/settings/{setting}")
    public void clearSetting(@PathVariable("name") String name, @PathVariable("setting") String setting,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeSetting(name, setting, "", key, request, response);
    }

    private void writeSetting(String name, String setting, String value, String key, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        if (service == null) {
            return;
        }
        RepositoryRequests.rejectTraversal(name);
        service.saveSetting(name, setting, value);
        response.setStatus(200);
    }

    /** A value the catalogue refuses, a missing project or a malformed name is the caller's to fix: {@code 400},
     *  naming what was refused. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void refused(IllegalArgumentException refused, HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(refused.getMessage() == null ? "refused" : refused.getMessage());
    }

    /** One project setting as the API answers it - the shape {@code GET /api/settings} lists a setting in, so a client
     *  reads either the same way. */
    /** A project's creation: optionally the settings it is created with. */
    public record ProjectRequest(Map<String, String> settings) {
    }

    public record SettingView(String key, String kind, String value, String defaultValue, boolean overridden,
                              boolean appliesImmediately, boolean pinned, String pinnedBy, String group, String label,
                              String description, boolean advanced) {

        static SettingView of(SettingsAdmin.SettingView view) {
            return new SettingView(view.key(), view.kind(), view.value(), view.defaultValue(), view.overridden(),
                    view.live(), view.pinned(), view.pinnedBy(), view.group(), view.label(), view.description(),
                    view.advanced());
        }
    }

    /**
     * It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @PostMapping("/api/cache/projects/{name}/evict/size")
    public Map<String, Object> enforceSizeCap(@PathVariable("name") String name,
                                              @RequestHeader(value = Repositories.KEY, required = false) String key,
                                              HttpServletRequest request,
                                              HttpServletResponse response) throws IOException {
        return pass(name, key, request, response, CacheService::enforceSizeCap);
    }

    /**
     * It reads the tenant's and the deployment's settings documents, which the values it resolves inherit from: one
     * object per module under a constant prefix, narrow by construction.
     */
    @PostMapping("/api/cache/projects/{name}/evict/ttl")
    public Map<String, Object> expireTtl(@PathVariable("name") String name,
                                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        return pass(name, key, request, response, CacheService::expireTtl);
    }

    @PostMapping("/api/cache/projects/{name}/evict/clear")
    public Map<String, Object> clear(@PathVariable("name") String name,
                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        return pass(name, key, request, response, CacheService::clearAll);
    }

    @PostMapping("/api/cache/projects/{name}/recount")
    public Map<String, Object> recount(@PathVariable("name") String name,
                                       @RequestHeader(value = Repositories.KEY, required = false) String key,
                                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        return pass(name, key, request, response, CacheService::recount);
    }

    /**
     * Delete the project: its entries, its cache settings and its stored figures. Like a sweep it starts in the
     * background and answers whether this call started it - {@code false} while a pass runs on the project - and the
     * project is gone from {@code GET /api/cache/projects} once it lands. A credential's grant naming the project is
     * left where it is.
     */
    @DeleteMapping("/api/cache/projects/{name}")
    public Map<String, Object> delete(@PathVariable("name") String name,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        if (service == null) {
            return Map.of();
        }
        RepositoryRequests.rejectTraversal(name);
        return Map.of("project", name, "started", service.deleteProject(name));
    }

    /** One shape for the four passes: they all start work and answer whether this call is the one that started it. */
    private Map<String, Object> pass(String name, String key, HttpServletRequest request,
                                     HttpServletResponse response, Pass pass) throws IOException {
        CacheService service = service(key, request, response);
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
    private CacheService service(String key, HttpServletRequest request, HttpServletResponse response) {
        CacheStorage cache = storage.getIfAvailable();
        if (cache == null) {
            response.setStatus(501);
            return null;
        }
        String tenant = routing.tenant(request);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        String actor = key == null ? "anonymous" : Authorization.hash(key);
        // A project's policy is its project settings; the settings are read and written over the same root store the
        // cache delegates into, validated through the catalogue, and audited as this request's writes.
        SettingsAdmin settings = new SettingsAdmin(root, _ -> Optional.empty(), List::of, audit, () -> tenant,
                () -> actor);
        return new CacheService(cache.scope(tenant), audit, () -> tenant, () -> actor, settings, passes);
    }
}
