/**
 * The cache storage: one provider delegating to the repository's own artifact store - a
 * {@link build.jenesis.repository.cache.storage.CacheStorageProvider} that builds {@code DelegatingCacheStorage} over a
 * segment of the {@code ArtifactStore} the node resolved. It depends on no cloud SDK: the SDK wiring lives once, in the
 * store backends.
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
    requires build.jenesis.repository.settings;
    provides build.jenesis.repository.cache.storage.CacheStorageProvider with
            build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorageProvider;
    provides build.jenesis.repository.settings.SettingsContributor with
            build.jenesis.repository.cache.storage.delegating.ProjectSettingsContributor;
    exports build.jenesis.repository.cache.storage.delegating;
}
