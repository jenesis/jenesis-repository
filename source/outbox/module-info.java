/**
 * A durable, retrying delivery queue over the artifact store, generic in what it queues.
 *
 * <p>Two modules kept one: forwarding queues a published path for a peer, the webhook module queues an event for an
 * endpoint, and each had its own copy of the same protocol - record, update by compare-and-set token, count
 * attempts, back off exponentially, park after a cap, move the parked entry out of the hot scan, unpark on an
 * operator's retry, drain a bounded window, and retain the parked backlog. Their javadocs cited each other as the
 * rule to mirror, which is the shape this repository has already caught drifting: the two {@code unpark} paths had
 * already diverged, one handling a re-publish that landed while an older entry sat parked and the other not.
 *
 * <p>The delicate part is shared exactly once here: the token-guarded park, whose parked copy is written before the
 * active one is removed and undone when the conditional removal loses, so an entry is never lost between the two
 * namespaces and a rival's fresh copy is never shadowed by a park of the superseded one. What each user keeps is its
 * own entry - the cargo differs entirely, a path and a blob hash against an event and its detail - and the mechanism
 * never looks inside it. The bound on the type parameter names precisely the bookkeeping the protocol reads (an
 * identity, the parked flag and when it parked, and how to unpark) and promises to read nothing else.
 *
 * <p>The parked backlog's retention is the mechanism's own dial and is declared here, once, rather than once per
 * user: the webhook outbox used to declare it and the forwarding outbox pruned nothing at all, and the settings
 * surface was the last place the two could have re-split after becoming one class. That is why this module reads
 * the settings and maintenance contracts where its sibling {@code bounds} stays at {@code java.base} plus the store.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.outbox {
    // The queue is a set of small store objects, so the store type appears in this module's constructor; a user
    // already requires the store to have one to hand over, so the dependency stays non-transitive.
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    exports build.jenesis.repository.outbox;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.outbox.OutboxSettings;
}
