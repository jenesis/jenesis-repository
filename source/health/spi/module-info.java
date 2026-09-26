/**
 * The maintainer-health ledger contract: the durable, per-coordinate record of the OpenSSF Scorecard-style health a
 * source scored for a coordinate's project - the shift from "probe deps.dev for maintainer-health every time the gate
 * or a panel looks" to "persist what was scored, serve it from the store". A {@link build.jenesis.repository.health.HealthLedger}
 * keyed by a repository's {@code (ecosystem, coordinate)} pair (version-independent - health is a property of the
 * project) keeps one {@link build.jenesis.repository.compliance.HealthSource.Health} record, written by the scheduled
 * health sweep and the publish-time persistence and refreshed by an explicit rescan; the ledger is itself a
 * {@link build.jenesis.repository.compliance.HealthSource}, so the compliance gate reads the persisted answer through
 * it instead of the live probe. A {@link build.jenesis.repository.health.HealthLedger#scanned health stamp} records the last refresh instant
 * for the staleness a Principle-10 view shows. With no persistence module installed {@code installed()} is empty and
 * every writer and surface degrades: nothing records, the endpoint answers the store is absent, and the gate falls back
 * to the live health source.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.health {
    // The shared ceiling the streaming default refuses past, applied through one call.
    requires build.jenesis.repository.bounds;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.compliance;
    exports build.jenesis.repository.health;
    uses build.jenesis.repository.health.HealthLedgerProvider;
}
