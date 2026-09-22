/**
 * The OCI retroactive-hold enablement, enterprise-only: a capability-only {@code RepositoryFormat} +
 * {@link build.jenesis.repository.blobs.BlobLayout} ({@link build.jenesis.repository.format.oci.inventory.OciBlobLayout})
 * that teaches the store-backed inventory OCI's on-store conventions so a retroactive KEV/license hold correctly
 * withholds and re-serves an OCI image. The free {@code OciFormat} implements neither layout SPI (a free module cannot
 * implement {@code BlobLayout}), so before this an OCI image whose CVE landed after push kept serving by
 * digest while the "held" gauge read clean (Audit-25 #9). This layout's {@code handles()} is ALWAYS false - it never
 * wins format dispatch, never proxies, never imports; it exists only to answer the inventory's capability lookups
 * ({@code describe}/{@code servedPaths}/{@code blobKeys}/{@code blobHashes}) reached through the non-handling-BlobLayout
 * fallback seams. It re-reads the documented store-key conventions ({@code blobs/<hex>}, {@code oci/<name>/tags/<tag>}
 * at {@code sha256:<hex>}) exactly as {@code HoldLifecycle.releaseOci} already duplicates them, rather than reaching
 * into the free module (which exports its package only to its own test); the manifest <em>document</em>'s dialect is
 * not re-read at all since the earlier work - the config, layer and sub-manifest digests come from the free
 * {@code BlobReferences} seam, resolved at call time, which is why this module requires no JSON parser of its own.
 * <p>It used to carry a walk consumer of its own that recorded the {@code published/} inventory row for tagged
 * images, by parsing OCI keys inside a consumer written for OCI. Nineteen other blobs-namespace layouts had the
 * same gap and none of that repair, so the parse moved to the layout - {@code BlobLayout.describePointer}, which
 * {@code OciBlobLayout} answers from the same {@code tagPointer} grammar - and one
 * {@code InventoryBackfillConsumer} now walks the pointers once for every format that can name its own keys.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.oci.inventory {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.format.oci.inventory to build.jenesis.repository.server.kernel.test,
            // the walk-consumer census and OciInventoryBackfillFixture, which names this module's consumer
            // as the edition's one adopter, moved to test/server-principles.
            build.jenesis.repository.server.principles.test,
            build.jenesis.repository.server.kernel.contract.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.oci.inventory.OciBlobLayout;
}
