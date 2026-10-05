/**
 * A release's closure taken from the lock file it carries, over a real filesystem store holding npm and Cargo
 * releases and cached copies: each package the lock installs placed as the holding the store keeps of it, at its
 * distance from the root, two versions of one package each placed, and what the lock leaves out of a consumer's build
 * left out of the closure.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.closure.lock
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.closure.lock.test {
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.closure.lock;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.cargo;
    requires build.jenesis.repository.format.npm;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.apache.commons.compress;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
