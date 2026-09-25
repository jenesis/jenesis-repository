package build.jenesis.repository.events;

import module java.base;

/**
 * The durable read a subscriber reconciles one {@link EventType} against - the documented answer to "I cannot miss
 * one".
 *
 * <h2>Why this exists as a route and not as a stronger delivery guarantee</h2>
 *
 * <p>Event delivery has two gaps, and only one of them is closable. Delivery <em>out of</em> the outbox is
 * at-least-once and repaired by the drain, so a subscriber sees duplicates rather than losses. Delivery <em>into</em>
 * the outbox is not: every producer emits after its own durable mutation, so a crash between that mutation and the
 * sink's note losing the process loses the event permanently. {@link EventSink}'s clause 12 states why that cannot be
 * healed - an event is a point-in-time observation the store never records as having been owed, so there is nothing to
 * re-derive it from, and inventing an intent record that could not distinguish an orphan from a real publish would
 * announce artifacts that never became visible. That is a worse guarantee, not a stronger one.
 *
 * <p>So the gap is irreducible, and the honest deliverable is the route around it rather than a mechanism that
 * pretends it is closed. What makes the route possible is that the blast radius is bounded to the <em>push</em> and
 * never to the fact: every event type has a durable, queryable counterpart the emit does not gate. A subscriber that
 * must be complete polls that counterpart and treats the webhook as an accelerator - the push tells it <em>when</em>
 * to look, the ledger tells it <em>what is true</em>. A subscriber that can tolerate a miss needs none of this.
 *
 * <p>The pairing is executable rather than prose because prose is what failed here: the claim "every event type has a
 * durable counterpart" was already written on {@link EventSink} and named none of them, so no operator could act on
 * it. {@link #of(EventType)} switches over {@link EventType} with no default, so a new event kind does not compile
 * until someone has answered what a subscriber reconciles it against - which is the question a new event type is most
 * likely to leave unanswered.
 *
 * @param ledger the durable state that is authoritative for this event kind, in the store's own vocabulary
 * @param route  the read an integrator polls to see it
 * @param caveat what that read cannot tell you, so the route is not itself over-promised
 */
public record EventReconciliation(String ledger, String route, String caveat) {

    /** All three parts are required, and {@code null} is never a legal value for any of them (&sect;3): a route with
     *  no read is not a route, and a route with no caveat is the over-promise this type exists to prevent. */
    public EventReconciliation {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(caveat, "caveat");
    }

    /**
     * The reconciliation route for one event type. A total switch with no {@code default}: a new {@link EventType}
     * constant fails to compile here until its durable counterpart is named, so the contract's "every event type has
     * one" claim cannot quietly stop being true.
     */
    public static EventReconciliation of(EventType type) {
        return switch (type) {
            case PUBLISH -> new EventReconciliation(
                    "the repository's serving pointers - what a read of the artifact would resolve",
                    "GET /api/browse?repo=",
                    "the tree answers what serves NOW, not what was published and later removed, so a subscriber "
                            + "reconciling publishes sees a since-unpublished version as absent rather than as a "
                            + "publish it missed. Where the published-index module is enabled, GET /api/index is the "
                            + "cheaper sync: fetch the descriptor, diff, fetch only unseen chunks.");
            case UNPUBLISH -> new EventReconciliation(
                    "the same serving pointers - an unpublish is an absence, so absence is the record",
                    "GET /api/browse?repo=",
                    "absence does not distinguish an unpublish from a version that never existed, and it carries no "
                            + "instant. A subscriber that must know WHEN needs the audit trail (GET /api/audit) for "
                            + "the operator action that caused it, or its own prior snapshot to diff against.");
            case QUARANTINE -> new EventReconciliation(
                    "the live /quarantine hold pointers enriched by the audit/quarantine decision rows, plus those "
                            + "rows' recent REJECT page for the refusals that hold nothing",
                    "GET /api/quarantine?repo=",
                    "the queue (`events`) is keyed off the live hold pointers rather than off the log, so a hold "
                            + "whose log row was lost or aged out still appears - but a hold that has since been "
                            + "released or discarded has left the queue entirely. It answers what is held now, not "
                            + "everything ever held. A REFUSED artifact is never in it at all - it keeps no bytes and "
                            + "links no pointer - so its counterpart is the bounded `refusals` page of the durable "
                            + "log, which is subject to that log's retention.");
            case RELEASE -> new EventReconciliation(
                    "the hold's resolution: its /quarantine pointer is gone and the coordinate serves again",
                    "GET /api/quarantine?repo= (the hold has left the queue) with GET /api/browse?repo=",
                    "the two terminal outcomes are told apart by whether the coordinate serves, not by the queue - "
                            + "both leave it. The reviewer's own action is in the audit trail as quarantine.release, "
                            + "which is the only record that names who resolved it and when.");
            case DISCARD -> new EventReconciliation(
                    "the hold's resolution: its /quarantine pointer is gone and the coordinate does not serve",
                    "GET /api/quarantine?repo= (the hold has left the queue) with GET /api/browse?repo=",
                    "a discard also removes that path's quarantine log rows - the artifact is gone, so its trail goes "
                            + "with it - so the audit trail's quarantine.discard entry is the durable record that the "
                            + "discard happened at all.");
            case FINDING -> new EventReconciliation(
                    "the findings ledger - the categorize-never-discard record of what was found and by whom",
                    "GET /api/findings?repo=",
                    "a raw read of what has been recorded, not a converged view: a module enabled late over "
                            + "pre-existing artifacts has no rows until a scan runs, so an empty ledger means "
                            + "\"nothing recorded yet\" rather than \"clean\". GET /api/vulnerabilities is the "
                            + "surface that re-derives into this ledger.");
            case PROMOTION -> new EventReconciliation(
                    "the staging id markers, each carrying its terminal state",
                    "GET /api/repository/staging?repo=",
                    "a sealed marker is reaped once past its TTL, so a promotion older than that no longer appears "
                            + "here; beyond it the promoted artifacts themselves are the record. The audit trail's "
                            + "staging.promote entry is what names the operator and the instant.");
        };
    }

    /** Every event type paired with its route, in declaration order - what an operator-facing surface renders so an
     *  integrator can find the answer without reading this class. */
    public static Map<EventType, EventReconciliation> all() {
        Map<EventType, EventReconciliation> routes = new LinkedHashMap<>();
        for (EventType type : EventType.values()) {
            routes.put(type, of(type));
        }
        return Collections.unmodifiableMap(routes);
    }

    /** The one-line statement of the two gaps, so a surface rendering these routes says what they are a route
     *  <em>around</em> rather than presenting them as an optional extra. */
    public static String preamble() {
        return "Webhook delivery is at-least-once from the outbox onward, so a receiver must tolerate a duplicate; "
                + "and it is lossy into the outbox, because a producer emits after its own durable mutation and a "
                + "crash in that window loses the event with nothing able to re-derive it. A subscriber that cannot "
                + "miss one therefore reconciles against the durable read below and treats the webhook as an "
                + "accelerator rather than as the record.";
    }
}
