/**
 * The store-backed findings ledger over a real filesystem artifact store: a finding round-trips with every field,
 * two feeds' reports of one coordinate coexist with attribution, a re-record refreshes without losing history, a
 * superseded finding is marked-not-deleted, labels attach and refresh per source, the repository-wide walk filters
 * by coordinate/kind/source/category/severity - plus ServiceLoader discovery of the provider.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.findings
 * @jenesis.test build.jenesis.repository.findings.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.findings.test {
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.findings.store;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
