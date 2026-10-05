/**
 * The Maven closure in isolation over a real filesystem store: Maven Resolver reading the POMs the repository holds,
 * with parents, properties, managed versions and ranges applied as Maven applies them, and nothing fetched.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.closure.maven
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.closure.maven.test {
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.closure.maven;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
