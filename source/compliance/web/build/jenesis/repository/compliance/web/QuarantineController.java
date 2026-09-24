package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.ReviewQueue;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.gate.store.GatedRepository;
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
 * The quarantine review surface, peeled out of the {@code RepositoryController} monolith into its own thin
 * {@code web} adapter and contributed through the {@code ServerModuleProvider} seam: what the compliance gate held back
 * for a repository - on the publish path or the proxy fetch path - with the verdict and the reasons, so a reviewer can
 * release a held artifact into the layout or discard it - and, beside the queue, what it <em>refused</em> outright,
 * which keeps no bytes and so can never be in a queue at all. Both are recorded by the discovered gate screen into the
 * framework-free {@link QuarantineLog} resolved through {@link Repositories}; this adapter is the only Spring-facing
 * piece, and a release or discard writes an audit event as the privileged mutation it is. Every mapping is the same one
 * it carried in the monolith, gated {@code manage:read} (this GET) or {@code manage:write} (the release and discard) by
 * the security chain before the request is reached; a traversal-unsafe repository or tenant name is a {@code 400}.
 */
@RestController
public class QuarantineController {

    /** How many recent refusals the review surface lists beside the held queue - a bounded ledger page. */
    private static final int REFUSAL_LIMIT = 25;

    private final Repositories repositories;
    private final AuditTrail audit;

    public QuarantineController(Repositories repositories, AuditTrail audit) {
        this.repositories = repositories;
        this.audit = audit;
    }

    /** The first page of the queue - the embedding/test seam. */
    public QuarantineView quarantine(String repo, String key, HttpServletResponse response) throws IOException {
        return quarantine(repo, null, MAX_PAGE, key, response);
    }

    @GetMapping("/api/quarantine")
    @ResponseBody
    public QuarantineView quarantine(@RequestParam("repo") String repo,
                                     @RequestParam(value = "after", required = false) String after,
                                     @RequestParam(value = "limit", defaultValue = "500") int limit,
                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                     HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        QuarantineLog log = new QuarantineLog(repositories.store(tenant, repo));
        // One page by cursor: `after` is the previous answer's `next`, absent on the last page. The page is composed
        // by the gate's ReviewQueue - the same rows the console renders - so the two surfaces cannot drift.
        ReviewQueue.Page page = ReviewQueue.page(repositories.store(tenant, repo),
                after == null || after.isBlank() ? null : after, Math.clamp(limit, 1, MAX_PAGE));
        List<ReviewQueue.Row> events = page.rows();
        // A REFUSED artifact keeps no bytes and links no pointer, so it is in the queue above at no point in its life -
        // the durable QuarantineLog row is its entire record. Surface every recent REJECT row from that same
        // ledger, a bounded read of the recent page (no re-screen, no fetch): the licence the publish gate denied
        // pre-commit, the proxy screen's refusal, and the hardened leg's typed structural ones alike. It used to be
        // filtered to the hardened leg's rows, so a publish the gate refused outright appeared in NEITHER list and the
        // only party who ever learnt of it was the publisher, from its 422.
        List<ReviewQueue.Row> refusals = new ArrayList<>();
        for (QuarantineLog.Event refusal : log.refusals(REFUSAL_LIMIT)) {
            // A refusal holds no bytes and therefore no hold record: it carries no kinds by construction.
            refusals.add(new ReviewQueue.Row(refusal.when().toString(), refusal.path(), refusal.coordinate(),
                    refusal.verdict().name(), refusal.reasons(), List.of()));
        }
        return new QuarantineView(events, refusals, page.next());
    }

    /** The largest review-queue page served; a caller past it follows {@code next}. */
    private static final int MAX_PAGE = 1000;

    @PostMapping("/api/quarantine/release")
    public void releaseQuarantined(@RequestParam("repo") String repo,
                                   @RequestHeader(value = Repositories.KEY, required = false) String key,
                                   @RequestBody QuarantineRequest request,
                                   HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return;
        }
        RepositoryRequests.rejectTraversal(request.path());
        // Audit BEFORE the mutation, not after: a crash between the release and a trailing audit write would leave a
        // privileged mutation unrecorded. The audit trail is best-effort (a failed write never fails the release), so
        // recording first cannot block the release either - it only guarantees the release is never silently unaudited.
        audit(key, AuditActions.QUARANTINE_RELEASE, repo + request.path());
        new GatedRepository(repositories.writable(tenant, repo)).release(request.path());
        response.setStatus(200);
    }

    /** Discard a held path. Answers whether anything was actually held - the same answer the console reports - so a
     *  reviewer who discarded the wrong row, or raced another reviewer, is told nothing happened rather than that the
     *  discard happened; a stale discard strips nothing and is not an error. */
    @PostMapping("/api/quarantine/discard")
    @ResponseBody
    public Discarded discardQuarantined(@RequestParam("repo") String repo,
                                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                                        @RequestBody QuarantineRequest request,
                                        HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        RepositoryRequests.rejectTraversal(request.path());
        // Audit before the mutation for the same reason as release above: never let a crash end a privileged discard
        // unrecorded; the best-effort trail cannot block the discard.
        audit(key, AuditActions.QUARANTINE_DISCARD, repo + request.path());
        boolean discarded = new GatedRepository(repositories.writable(tenant, repo)).discard(request.path());
        response.setStatus(200);
        return new Discarded(request.path(), discarded);
    }

    /** A traversal-unsafe repository, tenant or path name is a {@code 400} - the same mapping the sibling
     *  {@code ProvenanceController} carries, so a rejected body path never surfaces as a {@code 500}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /**
     * Validates the named repository and resolves the request's tenant from the {@code Jenesis-Repository-Key} header,
     * answering {@code 400} for a traversal-unsafe repository or tenant name and {@code null} so the caller returns at
     * once. Rights are enforced by Spring Security before the request reaches the controller, so this makes no
     * authorization decision - the same guard the monolith carried, unchanged by the move.
     */
    private String access(String repo, String key, HttpServletResponse response) {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        return tenant;
    }

    /** Records the privileged review mutation against the acting key's tenant and hashed identity - the same audit
     *  choreography the monolith carried, so the trail is unchanged by the move. */
    private void audit(String key, String action, String target) {
        String tenant = repositories.tenant(key);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    /** The review surface's two halves, told apart by whether there is anything left to review: {@code events} are the
     *  artifacts currently <em>held</em> (the live hold pointers enriched from the log), each releasable or
     *  discardable; {@code refusals} are the recent {@code REJECT} decisions, which kept no bytes and can only be read.
     *  A refusal is in {@code refusals} whichever leg reached it - the publish gate's pre-commit denial, the proxy
     *  screen's, the hardened leg's typed structural refusal - because the durable log row is the only trace a refused
     *  artifact leaves anywhere. {@code next} resumes the review queue after this page's last hold; null on the
     *  last page. */
    public record QuarantineView(List<ReviewQueue.Row> events, List<ReviewQueue.Row> refusals, String next) {
    }

    /** A discard's answer: whether anything was held at the path. */
    public record Discarded(String path, boolean discarded) {
    }

    public record QuarantineRequest(String path) {
    }
}
