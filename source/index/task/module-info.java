/**
 * The published incremental repository index and its scheduled pass as a plugin module: it provides
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code index} (the
 * {@code Lease}-guarded background pass that publishes a Maven-Central-style, resumable index of a repository's
 * publications) plus its settings, so the neutral maintenance scheduler discovers the pass and a deployment without
 * this module simply publishes no index. The index is a chain of immutable, content-addressed Zstandard chunks -
 * <strong>independent frames plus a seekable-format seek table</strong>, so a consumer resumes decompression at a
 * frame boundary rather than re-reading a chunk from the start; each chunk is immutable and content-addressed, so a
 * transport-level HTTP {@code Range} over one is safe to layer on once the shared serving range seam reaches this
 * endpoint (the chunk serve writes a whole {@code 200} today) - carrying NDJSON of pointer metadata only (path, size,
 * SHA-256, coordinate, ecosystem, version, publish time; never an
 * artifact blob). A {@link build.jenesis.repository.index.IndexDescriptor descriptor} names the current chain, so a
 * consumer's sync is fetch-descriptor, diff, fetch-only-unseen-chunks. Incremental chunks are written past a durable
 * high-water mark; a periodic full-snapshot rebase resets the chain for fresh consumers and garbage-collects the
 * superseded chunks after a grace period. No database: the whole index lives in the same scoped store the repository
 * already writes to, committed through the store's compare-and-set, and the pass never buffers an artifact.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.index {
    requires build.jenesis.repository.index.keys;
    requires tools.jackson.databind;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires com.github.luben.zstd_jni;
    exports build.jenesis.repository.index;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.index.PublishedIndexTaskProvider;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.index.IndexRetractionObserver,
                    build.jenesis.repository.index.IndexPublicationObserver;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.index.IndexRebaseConsumer;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.index.PublishedIndexStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.index.IndexSettingsContributor;
}
