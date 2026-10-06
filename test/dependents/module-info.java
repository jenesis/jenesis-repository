/**
 * Tests of the declared-dependencies index and its pass over a real filesystem artifact store: what each published
 * version's manifest declared is inverted into a sharded "who declares a dependency on X" index, a version published
 * since the last full pass is picked up by an incremental one, a version no longer published is taken out on the next
 * full pass, and the read pages by cursor. No network and no framework - the index is exercised end to end through the
 * store SPI. The console's dependents screen and the API are driven through their controllers.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.dependents
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.dependents.test {
    requires build.jenesis.repository.dependents;
    // The console's dependents screen, driven through its controller over the web kit's in-process repositories.
    requires build.jenesis.repository.dependents.web;
    requires build.jenesis.repository.web.testkit;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
