/**
 * The store-backed consolidated metadata store over a real filesystem artifact store: a section round-trips, a
 * multi-section batch mutate is a single CAS, disjoint-section writers converge through a CAS retry (both
 * deterministically and under real concurrency), an unrecognised section survives a foreign writer's mutate
 * untouched (the carry property at the section level), a newer-format document is refused rather than
 * downgrade-rewritten, and the provider is ServiceLoader-discovered.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.metadata
 * @jenesis.test build.jenesis.repository.metadata.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.metadata.store.test {
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires tools.jackson.databind;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
