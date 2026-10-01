package build.jenesis.repository.cache.storage.delegating;

import module java.base;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.CacheStorageProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStoreProvider;

/**
 * The cache storage: the repository's own store, under one segment.
 *
 * <p>One provider naming no backend, not one per backend: a second selection would let a deployment configure two
 * backends reading the same {@code jenrepo.s3.*} keys, indistinguishable from a misconfiguration. With one store,
 * "nothing configured" and "more than one configured" are answerable.
 *
 * <p><b>A segment, not a second root</b>, under {@link Scopes#SYSTEM}: rooted at the store, the cache would read every
 * top-level directory as a project and the projects screen would walk every artifact blob. {@link ArtifactStore#scope}
 * nests, so the layout is {@code <store>/.system/cache/<tenant>/...}. The {@code .system} name is outside the
 * scope-name grammar, so no tenant can reach the space and no enumeration offers it as a tenant.
 *
 * <p>A cache found anywhere else is not migrated: it is a cache, so it refills.
 */
public final class DelegatingCacheStorageProvider implements CacheStorageProvider {



    /** The repository's backend selection; the cache has none of its own. */
    private static final String STORE_SELECTION = "jenrepo.store";

    @Override
    public String name() {
        return "delegating";
    }

    /** Empty: the artifact-store provider declares what it needs and {@link ArtifactStoreProvider#resolve} validates
     *  it, naming every missing key. */
    @Override
    public Set<String> requiredConfig() {
        return Set.of();
    }

    @Override
    public CacheStorage create(UnaryOperator<String> config) {
        return create(config, ArtifactStoreProvider.resolve(config.apply(STORE_SELECTION), config));
    }

    /** The cache's segment of the store the node hands it - the metered one, so the cache's operations are counted with
     *  the rest. */
    @Override
    public CacheStorage create(UnaryOperator<String> config, ArtifactStore store) {
        return new DelegatingCacheStorage(store.scope(Scopes.SYSTEM).scope(Scopes.CACHE));
    }
}
