/**
 * The cache storage: one provider, delegating to the repository's own artifact store. It {@code provides} one
 * {@link build.jenesis.repository.cache.storage.CacheStorageProvider} per backend name - {@code filesystem}, {@code s3},
 * {@code gcs}, {@code azure-blob} - each building the same {@code DelegatingCacheStorage} over an
 * {@code ArtifactStore} resolved from the deployment's own configuration.
 *
 * <p>This module replaces four backend modules that were a second implementation of the artifact store's storage.
 * It depends on no cloud SDK at all: the SDK wiring lives once, in the store backends, and is reached
 * here through {@code ArtifactStoreProvider.resolve}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.storage.delegating {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    provides build.jenesis.repository.cache.storage.CacheStorageProvider with
            build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorageProvider;
    exports build.jenesis.repository.cache.storage.delegating;
}
