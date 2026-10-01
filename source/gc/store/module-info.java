/**
 * The reference {@code GarbageCollector} ({@code mark-sweep}), riding the shared artifact walk, so the mark over the
 * pointer roots and the sweep over {@code blobs/} are ordered, resumable, segmented and multi-node-safe. The mark
 * writes append-only reference batches {@code gc/<pass>/refs/<hh>/...}, flushed before every walk checkpoint; the sweep
 * holds one leading-byte shard at a time. An unreferenced blob is first condemned ({@code gc/condemned/<hash>}) and
 * deleted only when a later pass confirms it, after a claim on the marker that a re-publish of the same bytes contends
 * for by compare-and-set - so a referenced, re-linked or in-flight blob is never deleted.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.gc.store {
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    // The reference-lending seam of the format SPI, never a format plugin: the collector parses no format's documents
    // and is handed its lenders by its provider.
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.gc.store to build.jenesis.repository.gc.test,
            build.jenesis.repository.format.oci.test;
    provides build.jenesis.repository.gc.GarbageCollectorProvider
            with build.jenesis.repository.gc.store.MarkSweepGarbageCollectorProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.gc.store.GarbageCollectorObservability;
}
