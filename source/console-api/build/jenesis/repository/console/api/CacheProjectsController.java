package build.jenesis.repository.console.api;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.kernel.SettingsEditor;
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
 * Build-cache project management over the API: the key-header twin of the console's project and eviction screens,
 * calling the same {@link CacheService}, so the capability can be scripted, run from CI and driven by the CLI.
 *
 * <p>The service is built per request because who acts is the calling surface's: the console names its signed-in
 * member, this names the presented key's hash, as the sibling API controllers do. It holds no state - the "a pass is
 * already running" guard is a stored marker with a stale-pass timeout - so surfaces and nodes see one answer.
 *
 * <p>An eviction call starts a pass and returns; the sweep walks the project's entries off the request path. The answer
 * is {@code started} - {@code false} means a pass was already running, never a failure - and the outcome is read from
 * the project's stats.
 *
 * <p>Every read of settings here reads the tenant's and the deployment's settings documents the values inherit from:
 * one object per module under a constant prefix, narrow by construction.
 */
@RestController
public class CacheProjectsController {

    private final ObjectProvider<CacheStorage> storage;
    private final AuditTrail audit;
    private final RepositoryRouting routing;
    private final CacheService.Passes passes;
    private final ArtifactStore root;
    /** The one place a setting is changed. */
    private final SettingsEditor editor;

    /**
     * The cache's segment of the store is wired by the console node, so a repository-only composition lacks it; it is
     * resolved lazily so that composition answers {@code 501} rather than failing to boot.
     *
     * <p>It is the <b>root</b> storage, scoped per request by the tenant the routing answers. The console's
     * request-scoped {@code cacheTenantStorage} takes its tenant from the session, which a headless call lacks and
     * which would name the session's tenant rather than the request's.
     */
    public CacheProjectsController(@Qualifier("cacheRootStorage") ObjectProvider<CacheStorage> storage,
                                   AuditTrail audit,
                                   RepositoryRouting routing, ArtifactStore root, SettingsEditor editor) {
        this(storage, audit, routing, root, editor, CacheService.Passes.BACKGROUND);
    }

    /** With how an eviction's pass is started: {@link CacheService.Passes#BACKGROUND} in the composition, or
     *  {@link CacheService.Passes#CALLING_THREAD} for a test over a temporary store, so the pass ends before the call
     *  returns. */
    public CacheProjectsController(ObjectProvider<CacheStorage> storage,
                                   AuditTrail audit,
                                   RepositoryRouting routing, ArtifactStore root, SettingsEditor editor,
                                   CacheService.Passes passes) {
        this.editor = editor;
        this.storage = storage;
        this.audit = audit;
        this.routing = routing;
        this.root = root;
        this.passes = passes;
    }

    /** Every project with its stored counts and caps - the project set, never its entries. */
    @GetMapping("/api/cache/projects")
    public List<CacheService.ProjectSummary> list(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws IOException {
        CacheService service = service(key, request, response);
        return service == null ? List.of() : service.listProjects();
    }

    /** Create a project - {@code POST /api/cache/projects?name=<project>}, optionally with a {@code {"settings":{...}}}
     *  body creating it with its own settings in one step: every value validated first, {@code 400} naming every
     *  refusal with nothing written, the settings stored before the project exists
     *  ({@link CacheService#createProject(String, Map)}). {@code 400} for a project that exists. */
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

    /** One project's detail. */
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

    /** A project's settings: every project setting in the catalogue with its effective value, what it would inherit
     *  from its tenant and the deployment, and whether it sets its own. */
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

    /** Set one project setting, validated through the catalogue: {@code 400} naming the refusal, nothing stored. */
    @PutMapping("/api/cache/projects/{name}/settings/{setting}")
    public void setSetting(@PathVariable("name") String name, @PathVariable("setting") String setting,
                           @RequestBody(required = false) Map<String, String> body,
                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                           HttpServletRequest request, HttpServletResponse response) throws IOException {
        String value = body == null || body.get("value") == null ? "" : body.get("value");
        writeSetting(name, setting, value, key, request, response);
    }

    /** Clear one project setting, so the project inherits its tenant's and the deployment's again. */
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

    /** A value the catalogue refuses, a missing project or a malformed name: {@code 400} naming what was refused. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void refused(IllegalArgumentException refused, HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(refused.getMessage() == null ? "refused" : refused.getMessage());
    }

    /** A project's creation: optionally the settings it is created with. */
    public record ProjectRequest(Map<String, String> settings) {
    }

    /** One project setting as the API answers it - the shape {@code GET /api/settings} lists a setting in. */
    public record SettingView(String key, String kind, String value, String defaultValue, boolean overridden,
                              boolean appliesImmediately, boolean pinned, String pinnedBy, String group, String label,
                              String description, boolean advanced) {

        static SettingView of(SettingsAdmin.SettingView view) {
            return new SettingView(view.key(), view.kind(), view.value(), view.defaultValue(), view.overridden(),
                    view.live(), view.pinned(), view.pinnedBy(), view.group(), view.label(), view.description(),
                    view.advanced());
        }
    }

    /** Start a pass enforcing the project's size cap. */
    @PostMapping("/api/cache/projects/{name}/evict/size")
    public Map<String, Object> enforceSizeCap(@PathVariable("name") String name,
                                              @RequestHeader(value = Repositories.KEY, required = false) String key,
                                              HttpServletRequest request,
                                              HttpServletResponse response) throws IOException {
        return pass(name, key, request, response, CacheService::enforceSizeCap);
    }

    /** Start a pass expiring the project's entries past their ttl. */
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

    /** Delete the project: its entries, cache settings and stored figures. It starts in the background and answers
     *  whether this call started it - {@code false} while a pass runs on the project. A credential's grant naming the
     *  project stays. */
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

    /** The four passes' one shape: start work and answer whether this call started it. */
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

    /** The tenant's cache service, or {@code null} with the status set when the request names no usable tenant. The
     *  actor is the presented key's hash, as the sibling controllers record it. */
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
        // A project's policy is its project settings, changed through the one settings editor and audited as this
        // request's.
        SettingsAdmin settings = new SettingsAdmin(root, editor, List::of, audit, () -> tenant, () -> actor,
                _ -> null);
        return new CacheService(cache.scope(tenant), audit, () -> tenant, () -> actor, settings, passes);
    }
}
