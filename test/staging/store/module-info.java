/**
 * Store-backed staging over a real filesystem artifact store: staged artifacts are held out of the release layout
 * and listed under their id, promotion publishes every one through its own format (a staged module jar gaining its
 * module view) and is terminal, dropping discards, and the sealed transitions are rejected.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.staging.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.staging.store.test {
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.staging.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.maintenance;
    requires org.slf4j;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides org.slf4j.spi.SLF4JServiceProvider
            with build.jenesis.repository.staging.store.test.CapturingLoggerFinder;
}
