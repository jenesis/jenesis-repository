/**
 * The published incremental repository index and its scheduled pass: a
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code index}, plus its settings.
 * The index is a chain of immutable, content-addressed Zstandard chunks - independent frames with a seekable-format
 * seek table - carrying NDJSON of pointer metadata only (path, size, SHA-256, ecosystem, coordinate, version, publish
 * time). A {@link build.jenesis.repository.index.IndexDescriptor descriptor} names the chain, so a consumer syncs by
 * fetching it, diffing, and fetching only unseen chunks. Incremental chunks are appended past a durable high-water
 * mark; a periodic rebase starts a fresh chain and deletes superseded chunks after a grace period. The index lives in
 * the repository's own scoped store, committed by compare-and-set; without this module no index is published.
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
