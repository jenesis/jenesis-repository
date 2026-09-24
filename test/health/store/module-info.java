/**
 * The store-backed maintainer-health ledger over a real filesystem artifact store, its scheduled sweep, and the gate
 * repoint: a coordinate's health round-trips with every field, a stale refresh never rolls it backwards, the sweep
 * populates the ledger and stamps its freshness while an unscored coordinate is left unrecorded.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.health
 * @jenesis.test build.jenesis.repository.health.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.health.test {
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.health.store;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
