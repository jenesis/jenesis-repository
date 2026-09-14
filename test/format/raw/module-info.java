/**
 * Focused unit tests for the generic (raw) format, driving {@link build.jenesis.repository.format.raw.RawFormat} and
 * {@link build.jenesis.repository.format.raw.RawImporter} directly through an in-memory {@code FakeExchange} and a fixed
 * {@code Fetcher} against a real {@code FilesystemArtifactStore} rooted at a
 * JUnit {@code @TempDir} - no HTTP server, no network: PUT/GET/HEAD/DELETE and directory listing, pull-through proxy
 * caching, and content-addressed import.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.raw
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.raw.test {
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
