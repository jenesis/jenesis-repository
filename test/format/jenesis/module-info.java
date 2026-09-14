/**
 * Focused unit tests for the Jenesis module layout, driving {@link build.jenesis.repository.format.jenesis.JenesisFormat}
 * through an in-memory {@code FakeExchange} and calling {@link build.jenesis.repository.format.jenesis.ModuleViewPublisher}
 * directly, against a real {@code FilesystemArtifactStore} rooted at a JUnit
 * {@code @TempDir}: the {@code /module/} and {@code /artifact/} publish-and-serve round trip, and the cross-publish that
 * links a modular jar by module name and version (and by name alone) over one content-addressed blob.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.jenesis
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.jenesis.test {
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
