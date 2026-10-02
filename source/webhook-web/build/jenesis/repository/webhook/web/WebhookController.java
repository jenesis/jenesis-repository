package build.jenesis.repository.webhook.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.events.EventReconciliation;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.webhook.WebhookOutbox;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The webhook outbox of one repository - each queued event, its delivery attempts, whether it is parked after a
 * terminal failure and the last error - read from {@link WebhookOutbox} over the repository's store. A parked entry has
 * hit its attempt cap and is skipped by every drain pass; {@code POST /api/webhook/retry} unparks it through
 * {@link WebhookOutbox#unpark(String)}, which clears the attempt count and the park but keeps the set of endpoints that
 * already took the event, so the next delivery pass re-sends only to the others. The security chain gates the read
 * {@code manage:read} and the retry {@code manage:write} in the acting tenant; a traversal-unsafe name answers
 * {@code 400} and a retry that finds nothing parked {@code 404}.
 */
@RestController
public class WebhookController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final AuditTrail audit;

    public WebhookController(Repositories repositories, RepositoryRouting routing, AuditTrail audit) {
        this.repositories = repositories;
        this.routing = routing;
        this.audit = audit;
    }

    /** One page of the repository's webhook outbox ({@code after}, {@code limit}) - queued, retrying or parked - so an
     *  operator can see a stuck delivery before retrying it. It renders stored entries only. The page is bounded
     *  because the parked backlog grows with every publish while an endpoint fails, and the view carries {@code more}
     *  and the {@code next} cursor so a capped answer cannot read as complete. */
    @GetMapping("/api/webhook")
    @ResponseBody
    public WebhookView webhook(@RequestParam("repo") String repo,
                               @RequestParam(value = "after", required = false) String after,
                               @RequestParam(value = "limit", required = false) Integer limit,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        // A withheld coordinate's name and path are not disclosed here, as on the other manage:read listings: a held
        // member's coordinate and path are blanked while its operational row stays so the operator can still retry it.
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repo));
        List<WebhookEntryView> entries = new ArrayList<>();
        WebhookOutbox.Window<WebhookOutbox.Entry> window = new WebhookOutbox(repositories.store(tenant, repo))
                .entries(after, page(limit));
        for (WebhookOutbox.Entry entry : window.entries()) {
            String coordinate = entry.coordinate();
            String path = entry.path();
            if (coordinate != null && !coordinate.isBlank()
                    && !disclosable(inventory, coordinate, entry.version())) {
                coordinate = null;
                path = null;
            }
            entries.add(new WebhookEntryView(entry.id(), entry.type(), path, coordinate,
                    entry.attempts(), entry.parked(), entry.parked() ? "parked" : "pending",
                    entry.delivered().size(), entry.occurredAt(), entry.lastError()));
        }
        return new WebhookView(entries, window.more(), window.next(), reconciliation());
    }

    /** The window this listing answers with by default, and the ceiling it clamps a caller's request to. */
    static final int DEFAULT_LIMIT = 100;

    static final int MAX_LIMIT = 500;

    /** The requested window clamped into range: an absent, zero or negative {@code limit} takes the default, and one
     *  above {@link #MAX_LIMIT} is capped rather than refused, since the {@code more} flag says there is a next
     *  page. */
    private static int page(Integer limit) {
        return limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /** The documented reconciliation route, rendered beside the queue because this is the surface an integrator is
     *  on when an event never arrived and the question is what to poll instead. Static data derived from the event
     *  types, so the read touches neither the store nor an external source. */
    private static ReconciliationView reconciliation() {
        List<ReconciliationRoute> routes = new ArrayList<>();
        EventReconciliation.all().forEach((type, route) ->
                routes.add(new ReconciliationRoute(type.wire(), route.ledger(), route.route(), route.caveat())));
        return new ReconciliationView(EventReconciliation.preamble(), routes);
    }

    /** Unparks a parked delivery for another drain attempt and audits it: {@code 200}, or {@code 404} when nothing
     *  parked is queued under the id - so a second retry of the same entry is a clean {@code 404}, never a duplicate
     *  delivery. */
    @PostMapping("/api/webhook/retry")
    public void retry(@RequestParam("repo") String repo,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      @RequestBody RetryRequest request,
                      HttpServletRequest http, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, http, response);
        if (tenant == null) {
            return;
        }
        if (request == null || request.id() == null || request.id().isBlank()) {
            response.setStatus(400);
            return;
        }
        boolean unparked = new WebhookOutbox(repositories.store(tenant, repo)).unpark(request.id());
        if (!unparked) {
            response.setStatus(404);
            return;
        }
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key),
                "webhook.retry", repo + '/' + request.id());
        response.setStatus(200);
    }

    /** Whether an entry's coordinate may be disclosed: its {@code coordinate:version} through the servable-name seam
     *  under {@code HIDE_WITHHELD}, so a withheld member's name is screened out while a servable coordinate, or one the
     *  inventory cannot place as a held member, stays. A probe that throws drops the name. */
    private static boolean disclosable(StoreRepositoryInventory inventory, String coordinate, String version) {
        // Always coordinate:version, with the colon even for an empty version, since the seam fails open on a bare
        // name.
        String display = coordinate + ":" + (version == null ? "" : version);
        try {
            return inventory.disclosableDisplay(display, ServableNames.Policy.HIDE_WITHHELD);
        } catch (IOException e) {
            return false;
        }
    }

    /** The webhook outbox of one repository: every queued entry (stable by id), and the reconciliation route a
     *  subscriber that cannot miss an event polls instead of relying on the push. */
    public record WebhookView(List<WebhookEntryView> entries, boolean more, String next,
                              ReconciliationView reconciliation) {
    }

    /** The documented route around the delivery gap: what the gap is, and the durable read per event type. */
    public record ReconciliationView(String delivery, List<ReconciliationRoute> routes) {
    }

    /** One event type's durable counterpart: the ledger that is authoritative for it, the read that shows it, and
     *  what that read cannot tell you. */
    public record ReconciliationRoute(String type, String ledger, String route, String caveat) {
    }

    /** The body of a retry: the outbox id of the parked entry to unpark. */
    public record RetryRequest(String id) {
    }

    /** One queued webhook delivery: its outbox id, the event type and path, the coordinate, how many attempts it has
     *  taken, whether it is parked (terminally failed) and its status text, how many endpoints already took it, when
     *  the event occurred (its freshness) and the last error if any. */
    public record WebhookEntryView(String id, String type, String path, String coordinate, int attempts,
                                   boolean parked, String status, int delivered, String occurredAt, String error) {
    }
}
