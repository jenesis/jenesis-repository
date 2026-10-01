/**
 * The maintainer-health ledger contract: the durable, per-coordinate record of the health a source scored for a
 * project, so the gate and panels read a stored answer instead of probing deps.dev per look. A
 * {@link build.jenesis.repository.health.HealthLedger} keyed by {@code (ecosystem, coordinate)} - health belongs to the
 * project, not a version - keeps one {@link build.jenesis.repository.compliance.HealthSource.Health}, written by the
 * sweep and the publish-time persistence and refreshed by a rescan; the ledger is itself a
 * {@link build.jenesis.repository.compliance.HealthSource}. Its
 * {@link build.jenesis.repository.health.HealthLedger#scanned health stamp} records the last refresh for views to show.
 * With no persistence module {@code installed()} is empty and everything falls back to the live source.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.health {
    // The shared ceiling the streaming default refuses past.
    requires build.jenesis.repository.bounds;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.compliance;
    exports build.jenesis.repository.health;
    uses build.jenesis.repository.health.HealthLedgerProvider;
}
