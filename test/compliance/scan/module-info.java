/**
 * The vulnerability report over a real filesystem store, driven through its static read with no findings module:
 * what it asks the live feeds about, and what it reports.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.scan
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.scan.test {
    requires build.jenesis.repository.closure.spi;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.scan;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
