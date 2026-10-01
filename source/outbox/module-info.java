/**
 * A durable, retrying delivery queue over the artifact store, generic in what it queues: record, update by
 * compare-and-set token, count attempts, back off exponentially, park after a cap, move the parked entry out of the hot
 * scan, unpark on an operator's retry, drain a bounded window and retain the parked backlog - one protocol for every
 * user, whose entries' cargo it never looks inside.
 *
 * <p>The delicate part lives here once: the token-guarded park, whose parked copy is written before the active one is
 * removed and undone when the conditional removal loses. The type parameter's bound names exactly the bookkeeping the
 * protocol reads - identity, the parked flag and when it parked, and how to unpark.
 *
 * <p>The backlog's retention is the mechanism's own dial, declared here once, which is why this module reads the
 * settings and maintenance contracts.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.outbox {
    // The queue is small store objects, so the store type is in the constructor; a user already holds a store, so the
    // dependency stays non-transitive.
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    exports build.jenesis.repository.outbox;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.outbox.OutboxSettings;
}
