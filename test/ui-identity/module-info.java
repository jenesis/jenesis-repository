/**
 * The super-admin set: who a deployment treats as holding every right, read from the configured ids and from
 * the store, and what it refuses when the configuration names nobody.
 *
 * <p>In process against the code under test, so it answers in the quickest lane.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.ui.identity
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.ui.identity.test {
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
