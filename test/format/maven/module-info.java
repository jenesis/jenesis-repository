/**
 * Focused unit tests for the Maven layout, driving {@link build.jenesis.repository.format.maven.MavenFormat} and
 * {@link build.jenesis.repository.format.maven.MavenImporter} through an in-memory {@code FakeExchange}, and exercising
 * {@link build.jenesis.repository.format.maven.MavenMetadata} directly, against a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir}: a jar
 * publishes and serves, a metadata upload is dropped and generated on read, import claims the maven formats, and
 * {@code maven-metadata.xml} is rendered in Maven version order.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.maven
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.format.maven.test {
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
