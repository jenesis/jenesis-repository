/**
 * Findings reported from outside about a version already served, over a real filesystem store with the findings
 * ledger installed: recorded under the scanner's name, decided by the gate a publish meets, withheld onto the review
 * queue when the gate would not admit them and released from it the ordinary way. A module of its own because the
 * ledger's store brings a hold observer onto the graph, and the gate's own suite asserts which hold kinds its graph
 * does not carry.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.gate
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.gate.reported.test {
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.findings.store;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    // The layout the published path describes itself through, so the version has a coordinate to report against.
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
