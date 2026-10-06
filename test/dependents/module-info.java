/**
 * Tests of the dependents API over a real filesystem artifact store, driven through its controller over the web kit's
 * in-process repositories: its refusals, its declared half saying when it is not built, a declared row confirmed
 * against what is still published, and each row's requirement judged against the version asked about. The declared
 * rows are seeded by the closure pass that keeps them.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.dependents.web
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.dependents.test {
    requires build.jenesis.repository.closure;
    // The installed requirement grammars the declared rows' marker reads.
    requires build.jenesis.repository.dependents.requirements;
    // The console's dependents screen, driven through its controller over the web kit's in-process repositories.
    requires build.jenesis.repository.dependents.web;
    requires build.jenesis.repository.web.testkit;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
