/**
 * The store-backed metadata store: it provides {@link build.jenesis.repository.metadata.MetadataProvider}, keeping
 * each coordinate version's metadata as one JSON document at the {@code meta/<ecosystem>/<enc(coordinate)>/<version>}
 * key the SPI fixes, mutated section by section under the store's compare-and-set so writers of disjoint sections (a
 * publish, an advisory sweep) converge instead of losing an update, and a multi-section batch commits in one cycle. It
 * declares the {@code meta} key-space ({@link build.jenesis.repository.metadata.store.MetadataStorageNamespace}) so the
 * orphan diagnostic and the operator purge see it, and reports document size and retries through
 * {@link build.jenesis.repository.observation.ObservabilitySource}
 * ({@link build.jenesis.repository.metadata.store.MetadataObservability}). With this module absent the metadata SPI
 * resolves to nothing and a consumer degrades.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.metadata.store {
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.metadata.store;
    provides build.jenesis.repository.metadata.MetadataProvider
            with build.jenesis.repository.metadata.store.StoreMetadataProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.metadata.store.MetadataStorageNamespace;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.metadata.store.MetadataObservability;
}
