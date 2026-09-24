/**
 * The console's domain layer over a real store: what an eviction plan selects and what it refuses to
 * touch, how a provisioning token is minted, matched and revoked, and what a credential service
 * refuses an anonymous caller.
 *
 * <p>In process against the code under test, so it answers in the quickest lane.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.ui.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.store.test {
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.cache.storage.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
