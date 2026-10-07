/**
 * The cache storage: {@code DelegatingCacheStorage}, built over a segment of the repository's own artifact store by
 * {@code DelegatingCacheStorage.over} - from the store the node already resolved, or from the configuration that
 * selects one. It is a factory rather than a discovered provider because nothing could replace it: a second provider
 * under another name was never chosen and one under the same name was refused as a duplicate. It depends on no cloud SDK: the SDK wiring lives once, in the
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
    provides build.jenesis.repository.settings.SettingsContributor with
            build.jenesis.repository.cache.storage.delegating.ProjectSettingsContributor;
    exports build.jenesis.repository.cache.storage.delegating;
}
