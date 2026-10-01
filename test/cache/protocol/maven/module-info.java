/**
 * Unit test for the Maven build-cache layout: the six segments it claims, the project it reads from the path
 * rather than from the presentation, and the two hashed key components.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.protocol.maven
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.protocol.maven.test {
    requires build.jenesis.repository.cache.protocol;
    requires build.jenesis.repository.cache.protocol.maven;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
