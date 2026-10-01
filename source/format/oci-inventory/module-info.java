/**
 * OCI's on-store conventions, taught to the store-backed inventory: a capability-only {@code RepositoryFormat} and
 * {@link build.jenesis.repository.blobs.BlobLayout}
 * ({@link build.jenesis.repository.format.oci.inventory.OciBlobLayout}) that lets a retroactive KEV or licence hold
 * withhold and re-serve an OCI image. It never dispatches; it answers the inventory's capability lookups through the
 * non-handling {@code BlobLayout} fallbacks, from the store-key conventions ({@code blobs/<hex>},
 * {@code oci/<name>/tags/<tag>} holding {@code sha256:<hex>}). The manifest's own digests come from the
 * {@code BlobReferences} seam at call time, so this module needs no JSON parser. Its tagged images reach the shared
 * inventory back-fill through {@code BlobLayout.describePointer}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.oci.inventory {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.format.oci.inventory;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.oci.inventory.OciBlobLayout;
}
