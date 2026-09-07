/**
 * The reference {@code GarbageCollector} ({@code mark-sweep}, discovered with {@code ServiceLoader}), riding the
 * shared artifact walk - never its own listing loop - so the mark over the caller's pointer roots and the sweep
 * over {@code blobs/} are both ordered, resumable, segmented and multi-node-safe. Sharded mark: references land as
 * append-only batch objects {@code gc/<pass>/refs/<hh>/...}, flushed before every walk checkpoint so no committed
 * cursor ever lies about an unflushed reference; sweep memory is one leading-byte shard at a time, O(N/256), never
 * an O(N) set. Condemn-then-collect: an unreferenced blob is first condemned ({@code gc/condemned/<hash>}, the
 * marker is the clock) and deleted only when a <em>later</em> pass confirms it still unreferenced - at least one
 * full collection interval of grace - with the marker re-read immediately before deletion and cleared on the write
 * path by every pointer link, so a referenced, re-linked or in-flight blob is never deleted. Settings:
 * {@code jenreg.gc.stride} (checkpoint stride of the collector's walk passes, default 20000).
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.gc.store {
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    // The reference-lending seam only: a format that serves out of the shared blobs/ namespace declares the roots it
    // pins blobs under and, through BlobReferences.references, the blobs its stored documents keep alive that no
    // pointer body names. This is the format SPI, never a format plugin - the collector still knows no format, parses
    // no format's documents and is handed its lenders by its provider.
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.gc.store to build.jenesis.repository.gc.test,
            build.jenesis.repository.format.oci.test;
    provides build.jenesis.repository.gc.GarbageCollectorProvider
            with build.jenesis.repository.gc.store.MarkSweepGarbageCollectorProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.gc.store.GarbageCollectorObservability;
}
