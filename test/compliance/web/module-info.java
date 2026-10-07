/**
 * The compliance API's answers over a real filesystem artifact store, driven through its controllers over the web
 * kit's in-process repositories: a version's closure as the closure pass recorded it, each state the version's
 * document can be in, and what each was screened through.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.web
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.web.test {
    requires build.jenesis.repository.closure.spi;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.web;
    requires build.jenesis.repository.compliance.scan;
    requires build.jenesis.repository.store;
    requires spring.context;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.findings.store;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.web.testkit;
    // The Maven layout a reported version's published path is read back to its coordinate through.
    requires build.jenesis.repository.format.maven;
    requires tools.jackson.databind;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
