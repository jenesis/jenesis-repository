package build.jenesis.repository.webhook.web;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.events.EventReconciliation;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.webhook.WebhookOutbox;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The event-webhook recovery surface, contributed through the {@code ServerModuleProvider} seam so the server names no
 * webhook endpoint and the route exists only when this module is installed. It reports the webhook outbox of one
 * repository - each queued event, how many delivery attempts it has taken, whether it is parked after a terminal
 * failure and the last error - read from the framework-free {@link WebhookOutbox} over the repository's scoped store
 * resolved through {@link Repositories}. A parked entry - one that hit its attempt cap and would otherwise sit inert
 * absent manual store surgery, skipped each drain pass by the delivery task's {@code eligible()} check - can be
 * unparked for another try through {@code POST /api/webhook/retry}, the one mutation here. The retry drives the webhook
 * core's own {@link WebhookOutbox#unpark(String)}: it clears the entry's attempt count and park while keeping its
 * already-delivered endpoint set, so the next background {@code WebhookDeliveryTask} pass re-sends only to the endpoints
 * that never took the event - a write-role, idempotent recovery that never duplicates a delivery nor loses history. The
 * GET is gated {@code manage:read} and the retry {@code manage:write} by the security chain before the request is
 * reached (a {@code /api/} management surface, not deployment-global, so scoped to the acting tenant like forwarding's
 * retry), with a traversal-unsafe repository or tenant name a {@code 400} and a retry that finds nothing parked a
 * {@code 404} rather than a {@code 500}.
 */
@RestController
public class WebhookController {

    private final Repositories repositories;
    private final AuditTrail audit;

    public WebhookController(Repositories repositories, AuditTrail audit) {
        this.repositories = repositories;
        this.audit = audit;
    }

    /** The webhook outbox of one repository - what is still queued, retrying with backoff or parked after a terminal
     *  failure - so an operator can see a stuck delivery before retrying it. A read: it renders the durably-stored
     *  entries only, with no delivery attempt on the read path (§10).
     *
     *  <p>Bounded, and the bound is shown. The window is one page of the outbox ({@code after}, {@code limit}) rather
     *  than the whole of it, because the parked backlog grows with every publish for as long as an endpoint is
     *  failing - the repository an operator opens this screen for is the one where the unbounded read costs most. The
     *  view carries {@code more} and the {@code next} cursor, so a capped answer cannot read as a complete one. */
    @GetMapping("/api/webhook")
    @ResponseBody
    public WebhookView webhook(@RequestParam("repo") String repo,
                               @RequestParam(value = "after", required = false) String after,
                               @RequestParam(value = "limit", required = false) Integer limit,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        // Served-view parity: a withheld coordinate's name/path must not be disclosed on this
        // manage:read outbox listing, exactly as the sibling manage:read served-listings (/api/lifecycle,
        // /api/dependents) screen through the same seam at the same scope. Each parked/queued entry's coordinate:version
        // is routed through inventory.disclosableDisplay under HIDE_WITHHELD (the membership policy, resolving the
        // coordinate's ecosystem by the shared bounded published/ probe, stats no blob); a held member's coordinate and
        // its served path are neutralised out of the view while the operational row (id, attempts, parked, last error)
        // stays so the operator can still retry it, and a servable coordinate - or a ghost the inventory cannot place as
        // a held member - is disclosed unchanged. A probe that throws drops the name (fail-closed).
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
     *  above {@link #MAX_LIMIT} is capped rather than refused - a client asking for more than it may have wants as
     *  much as it can get, and the {@code more} flag tells it there is a next page either way. */
    private static int page(Integer limit) {
        return limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /** The documented reconciliation route, rendered beside the queue because this is the surface an
     *  integrator is already on when the question arises - "an event never arrived, what do I poll instead?" The
     *  webhook module is off the console node, so it reports its own conditions on a surface it owns rather than
     *  through a {@code SafetyAdvisor} no screen would render. Static data derived from the {@code EventType} enum,
     *  so the read stays pure (&sect;10): it renders no store state and reaches no external source. */
    private static ReconciliationView reconciliation() {
        List<ReconciliationRoute> routes = new ArrayList<>();
        EventReconciliation.all().forEach((type, route) ->
                routes.add(new ReconciliationRoute(type.wire(), route.ledger(), route.route(), route.caveat())));
        return new ReconciliationView(EventReconciliation.preamble(), routes);
    }

    /** Unpark a parked webhook delivery for another drain attempt - the recovery for an entry that would otherwise stay
     *  inert. A {@code 404} when nothing parked is queued under the id (nothing to retry), otherwise {@code 200} after
     *  the entry is reset for retry and the mutation is audited. Idempotent: a second retry of an entry already unparked
     *  (no longer parked) is a clean {@code 404}, never a duplicate delivery or a {@code 500}. */
    @PostMapping("/api/webhook/retry")
    public void retry(@RequestParam("repo") String repo,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      @RequestBody RetryRequest request,
                      HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
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

    /** Validate the named repository and resolve the request's tenant from the key header, answering {@code 400} and
     *  {@code null} for a traversal-unsafe repository or tenant so the caller returns at once. Rights are enforced by
     *  the security chain before the request is reached, so this makes no authorization decision. */
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

    /** Whether a webhook entry's coordinate may be disclosed on this served listing: its {@code coordinate:version}
     *  display form routed through the shared servable-name enumeration seam under {@code HIDE_WITHHELD} (the same seam
     *  {@code /api/lifecycle} and {@code /api/dependents} apply), so a withheld member's name is screened out while a
     *  servable coordinate - or a ghost the inventory cannot place as a held member - stays disclosed. Fail-closed: a
     *  probe that throws drops the name. */
    private static boolean disclosable(StoreRepositoryInventory inventory, String coordinate, String version) {
        // Always a coordinate:version display (the same form /api/lifecycle passes): a colon is present even when the
        // version is empty, so this never hits the colon-less seam that would fail open on a bare name.
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
     *  the event occurred (its freshness, §10) and the last error if any. */
    public record WebhookEntryView(String id, String type, String path, String coordinate, int attempts,
                                   boolean parked, String status, int delivered, String occurredAt, String error) {
    }
}
