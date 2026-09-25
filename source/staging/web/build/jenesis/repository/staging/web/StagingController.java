package build.jenesis.repository.staging.web;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.staging.Staging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


/**
 * The staging HTTP surface, peeled out of the {@code RepositoryController} monolith into its own thin
 * {@code web} adapter and contributed through the {@code ServerModuleProvider} seam: a deploy lands under a staging
 * id (held, not resolvable), the ids are listed for review, and an id is promoted into the release layout or dropped.
 * A staged upload is {@code PUT /staging/<tenant>/<repository>/<id>/<path>} - the path a publish into the repository
 * would take, under the release's id - and its tenant is the one the deployment's routing decides for the URL, exactly
 * as for the repository's artifacts. Listing, promoting and dropping are operations on the repository,
 * {@code /api/repository/staging...?repo=<repository>}, whose tenant is the one the routing answers for a request that
 * names none. Both answer beside the repository rather than inside its URL space, so no artifact path of any format
 * can collide with them. The lifecycle itself is the framework-free
 * {@link Staging} implementation resolved per tenant-and-repository through {@link Repositories}; this adapter is the
 * only Spring-facing piece. With no staging module installed the endpoints answer {@code 501}, after Spring Security's
 * authorization check so a {@code 401}/{@code 403} still precedes. A repository name or tenant that is not
 * routable is the routing's {@code 400}; a promote or drop of an already-sealed id is a {@code 409}.
 */
@RestController
public class StagingController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final AuditTrail audit;

    public StagingController(Repositories repositories, RepositoryRouting routing, AuditTrail audit) {
        this.repositories = repositories;
        this.routing = routing;
        this.audit = audit;
    }

    @PutMapping("/staging/{tenant}/{repo}/{id}/**")
    public void stage(@PathVariable("repo") String repo, @PathVariable("id") String id,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        RepositoryRouting.Route route = routing.route(request);
        String tenant = route.tenant();
        Optional<Staging> staging = repositories.staging(tenant, repo);
        if (staging.isEmpty()) {
            respondStagingNotInstalled(response);
            return;
        }
        // The staged path is the one a client would publish to within the repository; it is staged as the
        // repository's format sees it, so the promotion lays it out as a publish into the repository would.
        Optional<RepositoryType> type = RepositoryDocument.read(repositories.store(tenant, repo))
                .flatMap(held -> RepositoryType.installed(held.format()));
        if (type.isEmpty()) {
            response.setStatus(404);
            return;
        }
        String releasePath = type.get().formatPath(route.path().substring(("/" + id).length()));
        RepositoryRequests.rejectRawTraversal(id);
        RepositoryRequests.rejectRawTraversal(releasePath);
        // Stream the staged deploy straight into the content-addressed store rather than buffering the body in heap.
        staging.get().stage(id, releasePath, request.getInputStream());
        response.setStatus(201);
    }

    @PostMapping("/api/repository/staging/{id}/promote")
    public void promote(@RequestParam("repo") String repo, @PathVariable("id") String id,
                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                        HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        Optional<Staging> staging = repositories.staging(tenant, repo);
        if (staging.isEmpty()) {
            respondStagingNotInstalled(response);
            return;
        }
        RepositoryRequests.rejectRawTraversal(id);
        staging.get().promote(id);
        // A promotion releases a staged set in full - a privileged mutation, so it writes an audit event (the siblings
        // LifecycleController / ForwardingController audit their mutations too).
        audit(tenant, key, "staging.promote", repo + "/" + id);
        response.setStatus(200);
    }

    @PostMapping("/api/repository/staging/{id}/drop")
    public void drop(@RequestParam("repo") String repo, @PathVariable("id") String id,
                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        Optional<Staging> staging = repositories.staging(tenant, repo);
        if (staging.isEmpty()) {
            respondStagingNotInstalled(response);
            return;
        }
        RepositoryRequests.rejectRawTraversal(id);
        staging.get().drop(id);
        // Dropping discards a staged set - a privileged mutation, audited like promote.
        audit(tenant, key, "staging.drop", repo + "/" + id);
        response.setStatus(200);
    }

    @GetMapping("/api/repository/staging")
    @ResponseBody
    public StagingList stagingList(@RequestParam("repo") String repo,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        Optional<Staging> staging = repositories.staging(tenant, repo);
        if (staging.isEmpty()) {
            respondStagingNotInstalled(response);
            return null;
        }
        // A window, never the whole set: the first LIST_WINDOW stagings in the store's order with whether more exist,
        // each row's item count capped so it never drains a staging's tree. The list used to take every id and walk
        // every tree for its count, on the request thread - the staging-reap canary measured it at a million open
        // stagings, and it did not answer.
        Staging.Window window = staging.get().ids(LIST_WINDOW);
        List<StagingEntry> entries = new ArrayList<>();
        for (String id : window.ids()) {
            entries.add(new StagingEntry(id, staging.get().state(id).name(),
                    staging.get().stagedAtMost(id, ITEM_CAP)));
        }
        return new StagingList(entries, window.more());
    }

    /** The tenant an operation on the repository answers for - the routing's, for a request that names none in its
     *  URL - once the repository has been checked as a routable name, since it is about to scope the store. */
    private String tenant(String repo, HttpServletRequest request) {
        if (!Repositories.valid(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        return routing.tenant(request);
    }

    /** Record a privileged staging mutation on the acting tenant, attributing it to the key's credential hash (or
     *  {@code anonymous} on a non-enforcing deployment) - best-effort and a no-op when no audit module is installed. */
    private void audit(String tenant, String key, String action, String target) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    /** With no staging module installed the endpoints answer 501, after the auth check so 401/403 still precede. */
    private static void respondStagingNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("staging is not installed on this deployment");
    }

    @ExceptionHandler(IllegalStateException.class)
    public void conflict(HttpServletResponse response) {
        response.setStatus(409);
    }

    /** A promote/drop with a traversal-unsafe id, or a stage with a traversal-unsafe id/release path, is a {@code 400}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** The staging list's window and the cap on a row's item count; a count at the cap reads as "cap or more". */
    static final int LIST_WINDOW = 200;
    static final int ITEM_CAP = 1000;

    public record StagingEntry(String id, String state, int items) {
    }

    /** The first {@link #LIST_WINDOW} stagings and whether more exist - a caller that needs the rest asks the
     *  console's paged view; this list is the operator's glance and the CLI's, never a mirror of the whole set. */
    public record StagingList(List<StagingEntry> repositories, boolean more) {
    }
}
