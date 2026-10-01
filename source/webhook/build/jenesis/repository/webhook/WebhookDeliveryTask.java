package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.events.EventType;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.outbox.OutboxSettings;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The background drain of the webhook {@link WebhookOutbox}, under the exclusive {@code webhook} lease so a replicated
 * deployment delivers from one node per interval. For each repository it reads the queued events and the tenant's
 * effective {@link WebhookEndpoint}s from {@code webhook-endpoints}, and delivers each eligible event to every endpoint
 * subscribing to its type, stamping the authoritative tenant and repository. At-least-once and idempotent: an event
 * every subscribing endpoint took is dropped, a transient failure retried with exponential backoff, a terminal one
 * parked for the status surface, and the delivered-endpoint set kept per entry so a retry re-sends only where it was
 * not taken. An event no endpoint subscribes to is dropped, and a repository with no endpoint has its queue drained.
 *
 * <h2>Two per-tenant hazards, treated by what this side can judge</h2>
 * <ol>
 *   <li><b>A plaintext endpoint is refused</b>, since the URL states its transport: by the shared
 *       {@link build.jenesis.repository.settings.PrivateHostGuard} screen, in {@link WebhookDelivery#deliver}, so it is
 *       recorded as the entry's last error on {@code GET /api/webhook}. {@code webhook-allow-internal},
 *       deployment-global so no tenant admin can take it alone, permits a trusted internal receiver.</li>
 *   <li><b>An unsigned endpoint is reported, not refused</b>: whether a secretless {@code https} URL is safe depends on
 *       the receiver, so the pass publishes {@code jenrepo.webhook.unsigned} ({@link WebhookEndpoint#signed()}).</li>
 * </ol>
 *
 * <h2>The gap this pass cannot close</h2>
 * <p>Delivery out of the outbox is at-least-once; delivery into it is not. A producer records its note after its own
 * durable mutation, so a crash between loses the event, which nothing can re-derive ({@code EventSink} clause 12). So
 * "I cannot miss one" is answered by reconciliation: {@link build.jenesis.repository.events.EventReconciliation} names
 * the durable ledger and read for each event type, rendered on {@code GET /api/webhook} beside this queue.
 */
public final class WebhookDeliveryTask implements MaintenanceTask {

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
        // The HMAC secret lives in the separate webhook-secrets SECRET setting, keyed by URL, redacted on read-back and
        // kept out of exports; an endpoint with a matching secret signs.
        Map<String, String> secrets = WebhookEndpoint.secrets(context.config().apply("webhook-secrets"));
        List<WebhookEndpoint> configured = WebhookEndpoint.parse(spec).stream()
                .map(endpoint -> endpoint.withSecret(secrets.get(endpoint.url().toString())))
                .toList();
        List<WebhookEndpoint> endpoints = configured.stream()
                // Only the host half of the screen here: an unresolvable or internal-resolving host may be a DNS blip,
                // so the endpoint is held for a later pass without spending attempts. The plaintext half is permanent
                // and goes to WebhookDelivery.deliver, which records the refusal where an operator reads it.
                .filter(endpoint -> allowInternal || !endpoint.internal())   // no SSRF to loopback/metadata/private hosts
                .toList();
        // The parked backlog's bound, on the pass that fills it: an endpoint that is gone parks an entry per event, so
        // without it the backlog grows with publish traffic. Same lease and cadence as the drain.
        int reclaimed = outbox.prunePark(context.now(),
                OutboxSettings.PARKED_RETENTION.resolve(context.config()).orElse(null),
                OutboxSettings.parkedCap(context.config()));
        context.gauge("jenrepo.webhook.parked.reclaimed",
                "Parked webhook deliveries removed by the backlog's retention this pass",
                Map.of("repository", context.repository()), reclaimed);
        // An endpoint without a secret is delivered unsigned, so its receiver cannot tell a genuine event from a forged
        // POST; not refusable (WebhookEndpoint.signed()), so reported over this tenant's own endpoints.
        context.gauge("jenrepo.webhook.unsigned", "Configured webhook endpoints delivered without a signature",
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
                      // Every line unparseable is a misconfiguration, not "no endpoints": the queue is kept for a
                      // corrected config.
        }
        if (endpoints.isEmpty()) {
            depths(context, outbox.active().size(), outbox.parkedCount());   // retained, so the depth is real
            return;   // endpoints ARE configured but all currently filter out as internal/unresolvable (e.g. a
                      // Endpoints are configured but all filtered as internal or unresolvable, perhaps transiently: the
                      // queue is kept.
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
                // No endpoint subscribes to this type: dropped. Untokened, since the id is a digest of the event's
                // identity, so a rival object is the same event of the same type.
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
                // Every subscribing endpoint took it: dropped, but only if it is still the object this pass read, since
                // a concurrent unpark writes a fresh copy an operator just asked for. The drop takes any parked twin
                // with it.
                outbox.removeDelivered(entry.id(), item.token());
                continue;
            }
            WebhookOutbox.Entry updated = entry.withDelivered(delivered);
            if (failure != null) {
                updated = updated.withFailure(now, baseBackoffMillis, capBackoffMillis, maxAttempts, failure);
            }
            if (updated.parked()) {
                // Retries exhausted: moved to the parked backlog, visible and recoverable through /api/webhook/retry,
                // and out of the active scan. park() compare-and-sets on the read token, so the transition is counted
                // only when it landed.
                if (outbox.park(updated, item.token()) && !entry.parked()) {
                    deliveries(context, "-", "parked");         // count the park transition once, only if it landed
                }
                continue;
            }
            if (!updated.equals(entry)) {
                // Compare-and-set on the read token: a concurrent unpark resets the attempts and backoff, and writing
                // this pass's stale progress over it would defer or re-park the delivery an operator asked for, a loss
                // rather than a duplicate.
                outbox.update(updated, item.token());
            }
            queued++;
        }
        depths(context, queued, outbox.parkedCount());
    }

    /** The depth gauges, emitted on every exit from a pass, so a drained queue is not mistaken for a module that
     *  stopped. The no-endpoint exit has just emptied the queue and reports nothing; the retaining exits report their
     *  real depth. */
    private static void depths(RepositoryContext context, long pending, long parked) {
        context.gauge("jenrepo.webhook.pending", "Events queued for webhook delivery",
                Map.of("repository", context.repository()), pending);
        context.gauge("jenrepo.webhook.parked", "Webhook entries parked after a terminal failure",
                Map.of("repository", context.repository()), parked);
    }

    /** Count one delivery transition ({@code jenrepo.webhook.deliveries}), tagged by endpoint and outcome
     *  ({@code delivered}/{@code failed}/{@code parked}). */
    private static void deliveries(RepositoryContext context, String endpoint, String outcome) {
        context.counter("jenrepo.webhook.deliveries", "Webhook delivery transitions",
                Map.of("endpoint", endpoint, "outcome", outcome), 1);
    }

    /** The {@link EventType} for a wire token, or {@code null} for an unknown one, which is delivered to every endpoint
     *  rather than dropped. */
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
