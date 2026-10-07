package build.jenesis.repository.cleanup.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.SettingsEditor;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The repository-maintenance surface - retention policy, the cleanup sweep and pins - contributed through the
 * {@code ServerModuleProvider} seam. Cleanup and retention run through the {@code RetentionSweeper} resolved by
 * {@link Repositories}; without a retention module those endpoints answer {@code 501}. The sweep and its dry run walk
 * every release, so they are {@link RepositoryCleanup}'s - runs off the request that every surface reads back, the
 * console's cleanup panel included - and these endpoints start them and answer their state. Pins run through the repository's {@link StoreRepositoryInventory}.
 *
 * <p>Every mapping operates on one repository, {@code /api/repository/...?repo=<repository>}, gated
 * {@code repository:read}/{@code repository:write} on it by the security chain as its artifacts are
 * ({@link RepositoryRouting#operated}), beside the repository rather than in its URL space so no artifact path can
 * collide. The tenant is the routing's for a request naming none, so tenants never sweep or pin into each other, and an
 * unroutable repository name is a {@code 400}.
 *
 * <p>Settings reads here are the tenant's and the deployment's settings documents - one object per module under a
 * constant prefix.
 */
@RestController
public class MaintenanceController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final LiveConfig live;
    /** The one place a setting is changed: a repository's retention rules are its settings. */
    private final SettingsEditor editor;
    private final AuditTrail audit;
    private final MaintenanceScheduler maintenance;

    /** The sweep and its dry run, the implementation the console's cleanup panel runs too. */
    private final RepositoryCleanup cleanup;

    public MaintenanceController(Repositories repositories, RepositoryRouting routing, LiveConfig live,
                                 SettingsEditor editor, ObservationRegistry observations, AuditTrail audit,
                                 MaintenanceScheduler maintenance) {
        this.editor = editor;
        this.repositories = repositories;
        this.routing = routing;
        this.live = live;
        this.audit = audit;
        this.maintenance = maintenance;
        this.cleanup = new RepositoryCleanup(repositories.retentionSweeper(), () -> maintenance, observations);
    }

    /** The tenant an operation answers for - the routing's, for a request naming none - once the repository name is
     *  checked as routable, since it is about to scope the store. */
    private String tenant(String repo, HttpServletRequest request) {
        if (!Repositories.valid(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        return routing.tenant(request);
    }

    /** Record a privileged maintenance mutation on the audit trail: a sweep, a retention change and a pin shape
     *  deletion as a storage purge does. */
    private void audited(String tenant, String key, String action, String detail) throws IOException {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, detail);
    }

    /** Says, on a start, whether this request started the run or found one already running. */
    public static final String REFRESH_HEADER = "Jenesis-Refresh";

    /**
     * Start a cleanup sweep over the repository - retention, then collection - off the request, and answer its state:
     * the sweep walks every release, so the answer is the run as it stands, with a header saying whether this request
     * started it. {@code GET} on the same path reads it until it lands.
     */
    @PostMapping("/api/repository/cleanup")
    @ResponseBody
    public RepositoryCleanup.View cleanup(@RequestParam("repo") String repo,
                                          @RequestHeader(value = Repositories.KEY, required = false) String key,
                                          HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String tenant = tenant(repo, request);
        if (!cleanup.installed()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        ArtifactStore store = repositories.store(tenant, repo);
        boolean started = cleanup.start(store, tenant, repo, retention(tenant, repo), maintenance.config(),
                UnaryOperator.identity());
        response.setHeader(REFRESH_HEADER, started ? "started" : "running");
        if (started) {
            audited(tenant, key, AuditActions.REPOSITORY_CLEANUP, repo + " (started)");
        }
        return cleanup.read(store, false);
    }

    /** The repository's last cleanup sweep, or the one running now. */
    @GetMapping("/api/repository/cleanup")
    @ResponseBody
    public RepositoryCleanup.View lastCleanup(@RequestParam("repo") String repo, HttpServletRequest request,
                                              HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        if (!cleanup.installed()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        return cleanup.read(repositories.store(tenant, repo), false);
    }

    /** Forget one ecosystem's durable records in this repository - the operator's retirement of data whose format
     *  module is gone or switched off, and the way out of the refusal every reclaiming pass gives an unplaceable
     *  ecosystem. {@code 409} while an installed format still places it; after it, the collector reclaims the
     *  now-unreferenced blobs, and the module purge ({@code POST /api/admin/purge}) reaps the remaining pointers and
     *  listings. */
    @PostMapping("/api/repository/forget-ecosystem")
    @ResponseBody
    public Map<String, Object> forgetEcosystem(@RequestParam("repo") String repo,
                                               @RequestParam("ecosystem") String ecosystem,
                                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        long removed;
        try {
            removed = new StoreRepositoryInventory(repositories.store(tenant, repo)).forgetEcosystem(ecosystem);
        } catch (IllegalStateException stillPlaced) {
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(stillPlaced.getMessage());
            return null;
        }
        audited(tenant, key, AuditActions.REPOSITORY_FORGET_ECOSYSTEM, repo + " " + ecosystem + " (" + removed + " records)");
        return Map.of("ecosystem", ecosystem, "removed", removed);
    }

    /** The last dry run of a cleanup sweep - what retention would evict and what the collector would reclaim - or
     *  the one running now; {@code refresh=true} starts one off the request, with a header saying whether this request
     *  started it. */
    @GetMapping("/api/repository/cleanup/plan")
    @ResponseBody
    public RepositoryCleanup.View cleanupPlan(@RequestParam("repo") String repo,
                                              @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
                                              HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String tenant = tenant(repo, request);
        if (!cleanup.installed()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        ArtifactStore store = repositories.store(tenant, repo);
        if (refresh) {
            boolean started = cleanup.startPlan(store, retention(tenant, repo), maintenance.config(),
                    UnaryOperator.identity());
            response.setHeader(REFRESH_HEADER, started ? "started" : "running");
        }
        return cleanup.read(store, true);
    }

    /** The repository's retention policy and where each rule comes from. */
    @GetMapping("/api/repository/retention")
    @ResponseBody
    public RetentionView retention(@RequestParam("repo") String repo,
                                   @RequestHeader(value = Repositories.KEY, required = false) String key,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        if (repositories.retentionSweeper().isEmpty()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        RetentionPolicy policy = retention(tenant, repo);
        return new RetentionView(policy.keepLast(),
                policy.maxAge() == null ? "" : policy.maxAge().toString(),
                policy.prereleaseExpiry() == null ? "" : policy.prereleaseExpiry().toString(),
                policy.notDownloadedFor() == null ? "" : policy.notDownloadedFor().toString());
    }

    /** Set a repository's retention rules, each given one as its repository setting: a value sets it here, an empty one
     *  clears it to inherit from the tenant and deployment, and {@code none} on a duration rule switches it off here; a
     *  rule not given is left alone. Every value is validated through the catalogue before any is stored, so a
     *  malformed or non-positive rule is a {@code 400} that never reaches a sweep. The change is recorded as each
     *  rule's setting change, as the console's retention page records it. */
    @PutMapping("/api/repository/retention")
    public void setRetention(@RequestParam("repo") String repo,
                             @RequestParam(value = "keepLast", required = false) String keepLast,
                             @RequestParam(value = "maxAge", required = false) String maxAge,
                             @RequestParam(value = "prereleaseExpiry", required = false) String prereleaseExpiry,
                             @RequestParam(value = "notDownloadedFor", required = false) String notDownloadedFor,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        if (repositories.retentionSweeper().isEmpty()) {
            respondRetentionNotInstalled(response);
            return;
        }
        Map<String, String> rules = new LinkedHashMap<>();
        given(rules, RetentionPolicy.KEEP_LAST, keepLast);
        given(rules, RetentionPolicy.MAX_AGE, maxAge);
        given(rules, RetentionPolicy.PRERELEASE_EXPIRY, prereleaseExpiry);
        given(rules, RetentionPolicy.NOT_DOWNLOADED_FOR, notDownloadedFor);
        try {
            editor.repository(tenant, repo, rules, false,
                    new SettingsEditor.Actor(tenant, key == null ? "anonymous" : Authorization.hash(key)));
        } catch (IllegalArgumentException e) {
            // A malformed or non-positive dial is a 400 and never stored, since a stored bad rule would fail - or,
            // inverted, mass-delete - at sweep time.
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(e.getMessage() == null ? "invalid retention policy" : e.getMessage());
            return;
        }
        response.setStatus(200);
    }

    private static void given(Map<String, String> rules, String rule, String value) {
        if (value != null) {
            rules.put(rule, value.trim());
        }
    }

    @PostMapping("/api/repository/pin")
    public void pin(@RequestParam("repo") String repo,
                    @RequestParam("ecosystem") String ecosystem,
                    @RequestParam("coordinate") String coordinate,
                    @RequestParam("version") String version,
                    @RequestHeader(value = Repositories.KEY, required = false) String key,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        try {
            new StoreRepositoryInventory(repositories.store(tenant, repo)).pin(ecosystem, coordinate, version);
        } catch (IllegalArgumentException e) {
            respondBadSegment(response, e);                  // a traversal-unsafe ecosystem/version is a 400
            return;
        }
        audited(tenant, key, AuditActions.REPOSITORY_PIN, repo + " " + ecosystem + ":" + coordinate + ":" + version);
        response.setStatus(200);
    }

    @DeleteMapping("/api/repository/pin")
    public void unpin(@RequestParam("repo") String repo,
                      @RequestParam("ecosystem") String ecosystem,
                      @RequestParam("coordinate") String coordinate,
                      @RequestParam("version") String version,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        try {
            new StoreRepositoryInventory(repositories.store(tenant, repo)).unpin(ecosystem, coordinate, version);
        } catch (IllegalArgumentException e) {
            respondBadSegment(response, e);                  // a traversal-unsafe ecosystem/version is a 400
            return;
        }
        audited(tenant, key, AuditActions.REPOSITORY_UNPIN, repo + " " + ecosystem + ":" + coordinate + ":" + version);
        response.setStatus(200);
    }

    private static void respondBadSegment(HttpServletResponse response, IllegalArgumentException e) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(e.getMessage() == null ? "invalid coordinate segment" : e.getMessage());
    }

    @GetMapping("/api/repository/pins")
    @ResponseBody
    public PinsView pins(@RequestParam("repo") String repo,
                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        return new PinsView(new StoreRepositoryInventory(repositories.store(tenant, repo)).pins());
    }

    /** Without a retention module the cleanup and retention endpoints answer {@code 501}, after the auth check. */
    private static void respondRetentionNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("retention is not installed on this deployment");
    }

    public record RetentionView(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
    }

    public record PinsView(List<String> pinned) {
    }
    /** The policy the repository runs under: its rules through its own settings over its tenant's and the deployment's,
     *  the chain the scheduled sweep and the console read too. */
    private RetentionPolicy retention(String tenant, String repo) {
        return live.retention(tenant, repo);
    }
}
