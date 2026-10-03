package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.HoldLifecycle;
import build.jenesis.repository.gate.store.ReviewQueue;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.gate.store.GatedRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The quarantine review API: what the gate held for a repository, on the publish or proxy path, with verdict and
 * reasons, released into the layout or discarded; and beside it what the gate refused, which keeps no bytes. Both come
 * from the {@link QuarantineLog} and the gate's review queue; a release or discard is audited. Gated
 * {@code manage:read} for the GET and {@code manage:write} for the mutations; an unsafe name is a {@code 400}.
 */
@RestController
public class QuarantineController {

    /** How many recent refusals the review surface lists beside the held queue - a bounded ledger page. */
    private static final int REFUSAL_LIMIT = 25;

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final AuditTrail audit;

    public QuarantineController(Repositories repositories, RepositoryRouting routing, AuditTrail audit) {
        this.repositories = repositories;
        this.routing = routing;
        this.audit = audit;
    }

    /** The first page of the queue. */
    public QuarantineView quarantine(String repo, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return quarantine(repo, null, MAX_PAGE, request, response);
    }

    @GetMapping("/api/quarantine")
    @ResponseBody
    public QuarantineView quarantine(@RequestParam("repo") String repo,
                                     @RequestParam(value = "after", required = false) String after,
                                     @RequestParam(value = "limit", defaultValue = "500") int limit,
                                     HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        QuarantineLog log = new QuarantineLog(repositories.store(tenant, repo));
        // One page of the gate's ReviewQueue by cursor, the rows the console renders.
        ReviewQueue.Page page = ReviewQueue.page(repositories.store(tenant, repo),
                after == null || after.isBlank() ? null : after, Math.clamp(limit, 1, MAX_PAGE));
        List<ReviewQueue.Row> events = new ArrayList<>();
        for (ReviewQueue.Row row : page.rows()) {
            events.add(served(tenant, repo, row));
        }
        // Every leg's recent refusals, whose log row is their only record.
        List<ReviewQueue.Row> refusals = new ArrayList<>();
        for (QuarantineLog.Event refusal : log.refusals(REFUSAL_LIMIT)) {
            refusals.add(served(tenant, repo, new ReviewQueue.Row(refusal.when().toString(), refusal.path(),
                    refusal.coordinate(), refusal.verdict().name(), refusal.reasons(), refusal.rules(), List.of())));
        }
        return new QuarantineView(events, refusals, page.next());
    }

    /** A row as the API reports it: its path the one a client names within the repository, which is also the path
     *  a release or a discard of it takes. */
    private ReviewQueue.Row served(String tenant, String repo, ReviewQueue.Row row) throws IOException {
        return new ReviewQueue.Row(row.when(), repositories.servedPath(tenant, repo, row.path()), row.coordinate(),
                row.verdict(), row.reasons(), row.rules(), row.holds());
    }

    /** The largest review-queue page served; a caller past it follows {@code next}. */
    private static final int MAX_PAGE = 1000;

    /**
     * Hold a version for review by hand - every file of it, whatever the gate found - as the key that asks, answering
     * whether anything was held: a version that serves no file holds nothing.
     */
    @PostMapping("/api/quarantine/hold")
    @ResponseBody
    public Held holdVersion(@RequestParam("repo") String repo,
                            @RequestHeader(value = Repositories.KEY, required = false) String key,
                            @RequestBody HoldRequest request,
                            HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, http, response);
        if (tenant == null) {
            return null;
        }
        if (request.ecosystem() == null || request.coordinate() == null || request.version() == null) {
            response.setStatus(400);
            return null;
        }
        String actor = key == null ? "anonymous" : Authorization.hash(key);
        audit(tenant, key, AuditActions.QUARANTINE_HOLD,
                repo + " " + request.ecosystem() + " " + request.coordinate() + ":" + request.version());
        boolean held = HoldLifecycle.holdVersion(repositories.writable(tenant, repo), request.ecosystem(),
                request.coordinate(), request.version(), actor);
        response.setStatus(200);
        return new Held(held);
    }

    /**
     * Release a held path, or every held file of a version, into the layout.
     */
    @PostMapping("/api/quarantine/release")
    public void releaseQuarantined(@RequestParam("repo") String repo,
                                   @RequestHeader(value = Repositories.KEY, required = false) String key,
                                   @RequestBody QuarantineRequest request,
                                   HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, http, response);
        if (tenant == null) {
            return;
        }
        List<String> paths = request.targets();
        paths.forEach(RepositoryRequests::rejectTraversal);
        GatedRepository gated = new GatedRepository(repositories.writable(tenant, repo));
        // A release releases the whole version, so a path a release earlier in this request took with it is done.
        Set<String> released = new HashSet<>();
        for (String path : paths) {
            String held = repositories.formatPath(tenant, repo, path);
            if (released.contains(held)) {
                continue;
            }
            // Audited first, so a crash never leaves it unrecorded; the trail is best-effort and cannot block it.
            audit(tenant, key, AuditActions.QUARANTINE_RELEASE, repo + path);
            released.addAll(gated.release(held));
        }
        response.setStatus(200);
    }

    /** Discards a held path, or every held file of a version, answering which were still held; a stale discard
     *  strips nothing and is not an error. */
    @PostMapping("/api/quarantine/discard")
    @ResponseBody
    public Discarded discardQuarantined(@RequestParam("repo") String repo,
                                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                                        @RequestBody QuarantineRequest request,
                                        HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, http, response);
        if (tenant == null) {
            return null;
        }
        List<String> paths = request.targets();
        paths.forEach(RepositoryRequests::rejectTraversal);
        GatedRepository gated = new GatedRepository(repositories.writable(tenant, repo));
        List<String> discarded = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        // A discard discards the whole version, so a path a discard earlier in this request took with it is done.
        Set<String> dropped = new HashSet<>();
        for (String path : paths) {
            String held = repositories.formatPath(tenant, repo, path);
            if (!dropped.contains(held)) {
                // Audited first, as the release is.
                audit(tenant, key, AuditActions.QUARANTINE_DISCARD, repo + path);
                dropped.addAll(gated.discard(held));
            }
            (dropped.contains(held) ? discarded : absent).add(path);
        }
        response.setStatus(200);
        return new Discarded(discarded, absent);
    }

    /** A traversal-unsafe repository, tenant or path name is a {@code 400}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** Records the privileged review mutation against the tenant it acted on and the acting key's hashed
     *  identity. */
    private void audit(String tenant, String key, String action, String target) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    /** The review surface: {@code events}, the held artifacts, each releasable or discardable; {@code refusals}, the
     *  recent refusals of every leg, read-only; {@code next}, the queue's cursor, {@code null} on the last page. */
    public record QuarantineView(List<ReviewQueue.Row> events, List<ReviewQueue.Row> refusals, String next) {
    }

    /** What a hold by hand acts on: one version of one coordinate. */
    public record HoldRequest(String ecosystem, String coordinate, String version) {
    }

    /** A hold's answer: whether anything was held. */
    public record Held(boolean held) {
    }

    /** A discard's answer: the paths it dropped held bytes at, and those at which nothing was held any more - already
     *  released or discarded, perhaps by another reviewer. */
    public record Discarded(List<String> discarded, List<String> absent) {
    }

    /** What a release or a discard acts on: one held {@code path}, or the {@code paths} of a version's held files -
     *  a version is released or discarded whole. Naming neither is a {@code 400}. */
    public record QuarantineRequest(String path, List<String> paths) {

        List<String> targets() {
            List<String> targets = new ArrayList<>();
            if (path != null) {
                targets.add(path);
            }
            if (paths != null) {
                targets.addAll(paths);
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("a release or a discard names a path or paths");
            }
            return List.copyOf(targets);
        }
    }
}
