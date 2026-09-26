/**
 * Focused unit tests for the OCI / Docker registry format, driving {@link build.jenesis.repository.format.oci.OciFormat}
 * and {@link build.jenesis.repository.format.oci.OciImporter} through an in-memory {@code FakeExchange} against a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir} - no
 * registry, no Docker daemon: the {@code /v2/} version probe, monolithic and chunked blob pushes with digest
 * verification, manifest push and pull by tag and by digest, the tag list, and content-addressed import. Plus the
 * reference set the format lends garbage collection ({@code BlobReferences}) - resolved from a tag pointer, from the
 * per-manifest media-type sidecar a digest-only image is reachable through, and through an image index - and the
 * end-to-end proof that a pushed image survives the two collection passes that used to reclaim its layers.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.oci
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.oci.test {
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    // That a pushed image keeps its blobs is a claim about this format AND the pass that reclaims blobs, so the
    // end-to-end leg drives the real mark-sweep collector over a real push: the reference set OciFormat lends is only
    // worth what the sweep does with it, and the two halves asserted apart is exactly how a wiring gap survives.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.walk.store;
    requires org.junit.jupiter;
    requires org.assertj.core;
    // WSPI.2 (b): a PublishInterceptor IS a PublicationObserver, discovered through the single seam and split into
    // the verdict chain by instanceof.
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.oci.test.OciScreenInterceptor,
                    build.jenesis.repository.format.oci.test.OciHoldInterceptor;
}
