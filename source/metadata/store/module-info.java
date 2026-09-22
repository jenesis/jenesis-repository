/**
 * The store-backed consolidated metadata store as a plugin module: it provides
 * {@link build.jenesis.repository.metadata.MetadataProvider}, keeping each coordinate version's metadata as one
 * JSON document at the {@code meta/<ecosystem>/<enc(coordinate)>/<version>} key the SPI codec fixes, mutated
 * section-by-section under the store's compare-and-set so disjoint-section concurrent writers (a publish, an
 * advisory sweep, an AI labeler) converge instead of losing an update, and a multi-section batch commits in one CAS
 * cycle. It declares the {@code meta} key-space in the storage manifest
 * ({@link build.jenesis.repository.metadata.store.MetadataStorageNamespace}) so the orphan diagnostic and operator
 * purge see it, and reports doc-size and CAS-retry signals through the registry-free
 * {@link build.jenesis.repository.observation.ObservabilitySource} seam
 * ({@link build.jenesis.repository.metadata.store.MetadataObservability}).
 *
 * <p>This is the library/foundation: no production path reads or writes the document yet, and per-version
 * reclamation (eviction collapsing to a {@code meta} document delete) lands with the eviction cutover. With
 * this module absent the metadata SPI resolves to nothing and a consumer degrades gracefully.
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
    exports build.jenesis.repository.metadata.store to
            build.jenesis.repository.metadata.store.test, build.jenesis.repository.reclamation.test,
            build.jenesis.repository.server.kernel.test;
    provides build.jenesis.repository.metadata.MetadataProvider
            with build.jenesis.repository.metadata.store.StoreMetadataProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.metadata.store.MetadataStorageNamespace;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.metadata.store.MetadataObservability;
}
