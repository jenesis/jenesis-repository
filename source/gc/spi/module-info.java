/**
 * The garbage-collection SPI: reclaiming blobs no live pointer references is a discovered, optional-unique capability
 * ({@code jenrepo.gc=<name>}), and no-op by absence - with no module installed nothing is reclaimed and the capability
 * surfaces say so. An explicitly selected collector that cannot be honoured fails at resolution instead.
 *
 * <p>{@code plan} is the dry run a console previews, {@code collect} computes and applies. The caller hands in the
 * pointer roots ({@code publish}, plus the roots blobs-namespace formats declare), since which namespaces hold serving
 * pointers is layout knowledge; a format whose blobs are reachable only through a stored document lends the rest
 * through {@code build.jenesis.repository.format.BlobReferences}. A referenced, relied-on or in-flight blob is never
 * deleted: the sweep and a publish relying on condemned bytes contend for the {@code gc/condemned/<hash>} marker by
 * compare-and-set.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.gc {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.gc;
    uses build.jenesis.repository.gc.GarbageCollectorProvider;
}
