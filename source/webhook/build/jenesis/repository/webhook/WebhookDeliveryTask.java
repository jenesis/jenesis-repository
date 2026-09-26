package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.events.EventType;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.outbox.OutboxSettings;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The background drain of the webhook {@link WebhookOutbox}, run by the neutral maintenance scheduler under the
 * exclusive {@code webhook} lease (the {@code forwarding}/{@code cleanup} pattern) so a replicated deployment
 * delivers from one node per interval. For each repository it reads the queued events and resolves that tenant's
 * effective {@link WebhookEndpoint}s from {@code webhook-endpoints}, then delivers each eligible event to every
 * endpoint that subscribes to its type - stamping the authoritative tenant and repository from the pass context.
 * Delivery is at-least-once and idempotent: a fully delivered event (taken by every subscribing endpoint) is
 * dropped, a transient failure is retried with exponential backoff, and a terminal failure is parked (kept, not
 * retried) for the status surface; the delivered-endpoint set is kept per entry, so a retry re-sends only to the
 * endpoints that never took it. An event no endpoint subscribes to is dropped rather than retried forever, and a
 * repository with no configured endpoint has its queue drained, so an enabled-but-unconfigured deployment
 * accumulates no outbox state.
 *
 * <h2>Two per-tenant hazards, treated differently because only one of them is judgeable</h2>
 * <ol>
 *   <li><b>A plaintext endpoint is refused.</b> {@code https} is stated by the URL, so this side can judge it, and it
 *       is refused through the shared {@link build.jenesis.repository.settings.PrivateHostGuard} screen the forwarding
 *       peer already applies to its own operator-supplied targets. The refusal happens in
 *       {@link WebhookDelivery#deliver} rather than in this pass's filter, so it is recorded as the entry's last error
 *       and reaches an operator on {@code GET /api/webhook}; the {@code webhook-allow-internal} opt-out - deployment-
 *       global, so no tenant admin can take it alone - permits it for a trusted internal receiver.</li>
 *   <li><b>An unsigned endpoint is reported, not refused.</b> Whether a secretless {@code https} URL is safe depends
 *       on the receiver, which this side cannot see, so the pass publishes {@code jenreg.webhook.unsigned} instead of
 *       guessing (see {@link WebhookEndpoint#signed()}).</li>
 * </ol>
 *
 * <h2>The delivery gap this pass cannot close, and the route around it</h2>
 *
 * <p>This drain makes delivery <em>out of</em> the outbox at-least-once: it retries, backs off, parks and can be
 * retried again, so a subscriber sees duplicates rather than losses. It can do nothing about delivery <em>into</em>
 * the outbox. Every producer records its note after its own durable mutation, so a crash in that window loses the
 * event with nothing able to re-derive it - an event is a point-in-time observation the store never records as having
 * been owed ({@code EventSink} clause 12). Retrying harder cannot reach a note that was never written.
 *
 * <p>So the answer to "I cannot miss one" is reconciliation rather than delivery, and it is documented rather than
 * implied: {@link build.jenesis.repository.events.EventReconciliation} names the durable ledger and the read for each
 * event type, and {@code GET /api/webhook} renders them beside this queue - the surface an integrator is already on
 * when an event fails to arrive. Consistent with how the unsigned-endpoint condition is handled above: a gap this
 * side cannot close is <em>reported</em> where the person who must act on it is looking, never papered over with a
 * mechanism that would have to invent the missing event to appear stronger.
 */
public final class WebhookDeliveryTask implements MaintenanceTask {

    /** How long a parked delivery is kept by default. Long enough that an integrator debugging a receiver outage
     *  still finds it days later, short enough that a decommissioned endpoint does not hold space indefinitely. */

    private final Duration interval;
    private final int maxAttempts;
    private final long baseBackoffMillis;
    private final long capBackoffMillis;
    private final WebhookDelivery delivery;

    public WebhookDeliveryTask(Duration interval, int maxAttempts, Duration baseBackoff, Duration capBackoff,
                               WebhookDelivery delivery) {
        this.interval = interval;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMillis = baseBackoff.toMillis();
        this.capBackoffMillis = capBackoff.toMillis();
        this.delivery = delivery;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        ArtifactStore store = context.store();
        WebhookOutbox outbox = new WebhookOutbox(store);
        boolean allowInternal = Boolean.parseBoolean(context.config().apply("webhook-allow-internal"));
        String spec = context.config().apply("webhook-endpoints");
        // The per-endpoint HMAC secret lives in the separate webhook-secrets SECRET setting (redacted on read-back,
        // kept out of exports), keyed by URL - attach it here so an endpoint with a matching secret signs and one
        // without stays unsigned, exactly as before the secret left the endpoints line.
        Map<String, String> secrets = WebhookEndpoint.secrets(context.config().apply("webhook-secrets"));
        List<WebhookEndpoint> configured = WebhookEndpoint.parse(spec).stream()
                .map(endpoint -> endpoint.withSecret(secrets.get(endpoint.url().toString())))
                .toList();
        List<WebhookEndpoint> endpoints = configured.stream()
                // The host half of the outbound screen only, and only here: an unresolvable or internal-resolving host
                // is a TRANSIENT condition (a DNS blip resolves every host to "internal"), so those endpoints are held
                // back for a later pass rather than burning an entry's attempts. The plaintext half is permanent - the
                // scheme will not change by itself - so it is NOT filtered here: it goes to WebhookDelivery.deliver,
                // which refuses it into the entry's last error where an operator can read it.
                .filter(endpoint -> allowInternal || !endpoint.internal())   // no SSRF to loopback/metadata/private hosts
                .toList();
        // The one condition this feature cannot refuse and cannot otherwise show: an endpoint with no matching
        // webhook-secrets entry is delivered without a signature, so its receiver cannot tell a genuine event from a
        // POST anyone who learns the URL can forge. It is not refusable (a secret-bearing https URL is authenticated
        // by possession and this side cannot tell one from a public URL - see WebhookEndpoint.signed()), so it is
        // reported: a standing per-repository reading beside the queue depths, resolved over exactly this tenant's
        // effective settings, bounded by the endpoints the tenant itself configured. A diagnostic, never a gate.
        // The parked backlog's own bound, applied on the same pass that fills it. Parking keeps a terminal failure
        // visible and retryable, which is right, but nothing ended its life: an endpoint that is permanently gone
        // parks one entry per event, so the backlog grows with publish traffic rather than with anything an operator
        // did. Reclaimed here rather than in a pass of its own - it is the same lease, the same cadence, and a sweep
        // that only runs when the queue is being worked is a sweep that cannot drift out of step with it.
        int reclaimed = outbox.prunePark(context.now(),
                OutboxSettings.PARKED_RETENTION.resolve(context.config()).orElse(null),
                OutboxSettings.parkedCap(context.config()));
        context.gauge("jenreg.webhook.parked.reclaimed",
                "Parked webhook deliveries removed by the backlog's retention this pass",
                Map.of("repository", context.repository()), reclaimed);
        context.gauge("jenreg.webhook.unsigned", "Configured webhook endpoints delivered without a signature",
                Map.of("repository", context.repository()),
                configured.stream().filter(endpoint -> !endpoint.signed()).count());
        if (spec == null || spec.isBlank()) {
            for (WebhookOutbox.Entry entry : outbox.entries()) {   // active AND parked - no endpoint at all, accumulate nothing
                outbox.remove(entry.id());
            }
            depths(context, 0, 0);                              // no endpoint at all, and the queue is gone with it
            return;
        }
        if (configured.isEmpty()) {
            depths(context, outbox.active().size(), outbox.parkedCount());   // retained, so the depth is real
            return;   // the setting has content but every line is unparseable (a malformed URL, a typo'd scheme):
                      // that is a misconfiguration, not "no endpoints", so retain the queue (active AND parked) and let
                      // a corrected config deliver these events on a later pass rather than silently purging deliverable
                      // entries - never a purge here
        }
        if (endpoints.isEmpty()) {
            depths(context, outbox.active().size(), outbox.parkedCount());   // retained, so the depth is real
            return;   // endpoints ARE configured but all currently filter out as internal/unresolvable (e.g. a
                      // transient DNS failure resolving every host): retain the queue and retry a later pass rather
                      // than permanently deleting events that will deliver once resolution recovers
        }
        List<WebhookOutbox.Queued<WebhookOutbox.Entry>> pending = outbox.queued();   // the hot scan: live/pending entries with the token each was read at
        if (pending.isEmpty()) {
            depths(context, 0, outbox.parkedCount());           // drained: nothing pending, parked entries remain
            return;
        }
        long now = context.now().toEpochMilli();
        int queued = 0;
        for (WebhookOutbox.Queued<WebhookOutbox.Entry> item : pending) {
            WebhookOutbox.Entry entry = item.entry();
            if (!entry.eligible(now)) {
                if (entry.parked()) {
                    outbox.park(entry, item.token());           // a pre-split leftover parked in the active queue - migrate it out of the scan
                } else {
                    queued++;                                   // still inside its backoff window - live, keep scanning it
                }
                continue;
            }
            EventType type = typeOf(entry.type());
            Set<String> relevantKeys = new TreeSet<>();
            List<WebhookEndpoint> relevant = new ArrayList<>();
            for (WebhookEndpoint endpoint : endpoints) {
                if (type == null || endpoint.subscribes(type)) {
                    relevant.add(endpoint);
                    relevantKeys.add(endpoint.key());
                }
            }
            if (relevant.isEmpty()) {
                // No endpoint subscribes to this type - drop, do not retry. Untokened deliberately: an entry's id is a
                // digest of the event's own identity, so a rival that replaced this object carries the SAME event and
                // therefore the same type, which no endpoint subscribes to either. There is no version of this entry
                // that would have been delivered, so there is nothing a compare-and-set could protect.
                outbox.remove(entry.id());
                continue;
            }
            Set<String> delivered = new TreeSet<>(entry.delivered());
            String failure = null;
            for (WebhookEndpoint endpoint : relevant) {
                if (delivered.contains(endpoint.key())) {
                    continue;                                   // a prior pass already delivered this event here
                }
                try {
                    delivery.deliver(endpoint, entry, context.tenant(), context.repository(), allowInternal);
                    delivered.add(endpoint.key());
                    deliveries(context, endpoint.key(), "delivered");
                } catch (IOException exception) {
                    failure = message(exception);
                    deliveries(context, endpoint.key(), "failed");
                }
            }
            if (delivered.containsAll(relevantKeys)) {
                // Every subscribing endpoint took it - drop it, but only if it is still the object this pass read.
                // A concurrent unpark (the retry endpoint, off this pass's lease) writes a fresh, immediately-eligible
                // copy; deleting that would discard a delivery an operator just asked for. CAS-guarded, and the drop
                // takes any parked twin with it.
                outbox.removeDelivered(entry.id(), item.token());
                continue;
            }
            WebhookOutbox.Entry updated = entry.withDelivered(delivered);
            if (failure != null) {
                updated = updated.withFailure(now, baseBackoffMillis, capBackoffMillis, maxAttempts, failure);
            }
            if (updated.parked()) {
                // Retries are exhausted: MOVE the entry out of the active queue into the parked backlog rather than
                // delete it, so a terminal failure stays visible on the status surface (outbox.entries()) and is
                // recoverable through /api/webhook/retry -> WebhookOutbox.unpark - the same park-and-recover semantics
                // forwarding gives. Parking it out of the active queue is the unbounded-scan fix: the hot drain scan
                // (outbox.active()) no longer re-lists and re-reads it every pass. The park transition is counted once.
                // Gate the park-transition metric on the CAS result, exactly as ForwardingTask does:
                // park() compare-and-sets on the read token, so a re-publish that replaced the entry mid-pass loses the
                // CAS and nothing is parked - counting it then would over-report the transition. Only meter a park that
                // actually landed (and only the first time this entry transitions to parked).
                if (outbox.park(updated, item.token()) && !entry.parked()) {
                    deliveries(context, "-", "parked");         // count the park transition once, only if it landed
                }
                continue;
            }
            if (!updated.equals(entry)) {
                // Compare-and-set on the token this pass read the entry at, exactly as ForwardingTask does. A
                // concurrent unpark from /api/webhook/retry - which runs off this pass's lease - resets the attempt
                // count and clears the backoff; writing this pass's stale progress over it would re-impose an attempt
                // bump and a backoff window on the delivery the operator just asked for, deferring or (at the cap)
                // re-parking it. That is a LOST delivery rather than a duplicated one, which is the wrong direction
                // for an at-least-once seam. A lost CAS drops this pass's progress; the rival's entry is
                // picked up whole next drain.
                outbox.update(updated, item.token());
            }
            queued++;
        }
        depths(context, queued, outbox.parkedCount());
    }

    /**
     * The depth gauges, emitted on <em>every</em> exit from a pass.
     *
     * <p>The peer of {@code ForwardingTask.depths}, and fixed with it because §13 makes them one mechanism. They
     * used to be written only where the pass found work, so each early return left the previous pass's numbers
     * standing and a drained queue was indistinguishable from a module that had stopped running. The same fix had
     * already hoisted {@code jenreg.webhook.unsigned} above these very returns for the same reason - the pattern was
     * understood here and simply not applied to the depths.
     *
     * <p>What is true at each exit differs: the no-endpoint exit has just emptied the queue and reports nothing,
     * while the two retain-on-misconfiguration exits keep theirs and report its real depth. Reporting zero there
     * would trade a stale gauge for a lying one.
     */
    private static void depths(RepositoryContext context, long pending, long parked) {
        context.gauge("jenreg.webhook.pending", "Events queued for webhook delivery",
                Map.of("repository", context.repository()), pending);
        context.gauge("jenreg.webhook.parked", "Webhook entries parked after a terminal failure",
                Map.of("repository", context.repository()), parked);
    }

    /** Count one webhook delivery transition ({@code jenreg.webhook.deliveries}), tagged by endpoint and outcome
     *  ({@code delivered}/{@code failed}/{@code parked}) - a monotonic counter beside the pending/parked gauges. */
    private static void deliveries(RepositoryContext context, String endpoint, String outcome) {
        context.counter("jenreg.webhook.deliveries", "Webhook delivery transitions",
                Map.of("endpoint", endpoint, "outcome", outcome), 1);
    }

    /** The {@link EventType} for a wire token, or {@code null} for an unrecognised one (delivered to every endpoint
     *  rather than dropped, so a forward-compatible event type is never silently lost). */
    private static EventType typeOf(String wire) {
        for (EventType type : EventType.values()) {
            if (type.wire().equals(wire)) {
                return type;
            }
        }
        return null;
    }

    private static String message(IOException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
