/**
 * The garbage-collection SPI: reclaiming the content blobs no live pointer references any more is a discovered,
 * optional-unique capability ({@code jenreg.gc=<name>} selects among installed collectors), kept separate
 * from its implementation so the reclamation strategy can change without breaking a caller - and <em>no-op by
 * absence</em>: with no module installed, {@code GarbageCollectorProvider.resolve} is empty, nothing is ever
 * reclaimed, and the capability surfaces say garbage collection is off. No blob is deleted by a deployment that did
 * not opt into a collector. A collector that <em>was</em> explicitly selected but cannot be honoured fails at
 * resolution instead, because a silent no-op reads as a healthy idle system while storage grows without bound
 * (&sect;9). A {@code GarbageCollector} matches the retention sweeper's shape - {@code plan} is the dry run a
 * maintenance console previews, {@code collect} computes and applies - and is handed the pointer roots by its
 * caller (always {@code publish}; a blobs-namespace format's declared roots are added by the caller that knows
 * them), because which namespaces hold serving pointers is layout knowledge this free primitive deliberately does
 * not have. Where naming a root is not enough - a format whose served blobs are reachable only through a stored
 * document, so no pointer body names them - that format lends the rest through
 * {@code build.jenesis.repository.format.BlobReferences}, and an implementation unions what it is told with the
 * hashes it read: the derivation stays with the format, never in the collector.
 * Deletion is the one unrecoverable act in the product, so an implementation is held to the invariant
 * that a referenced, re-linked or in-flight blob is never deleted; the write path cooperates by clearing the
 * collector's {@code gc/condemned/<hash>} marker whenever a pointer links a blob ({@code Publication.link}).
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.gc {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.gc;
    uses build.jenesis.repository.gc.GarbageCollectorProvider;
}
