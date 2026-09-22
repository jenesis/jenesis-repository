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
 * <p>There used to be four providers here - {@code filesystem}, {@code s3}, {@code gcs}, {@code azure-blob} - each
 * naming a backend and each resolving the artifact store of the same name, selected by a
 * {@code jenreg.cache-storage} key of the cache's own. That second selection was never intended and nothing reached
 * it: the Helm chart offers no cache backend at all, so no deployment could choose one, and the only consumers of
 * the three object-store providers were the contract legs written for them.
 *
 * <p>What it cost was ambiguity of the kind that loses data. Both roles read the same {@code jenreg.s3.*} keys, so a
 * deployment with the repository on disk and the cache in a bucket configured two backends at once - and nothing
 * could tell that apart from a misconfiguration. With one selection there is one store, and "nothing configured"
 * and "more than one configured" become answerable questions rather than indistinguishable ones.
 *
 * <p><b>A segment, not a second root.</b> The cache takes one space inside the repository's store rather than
 * a root of its own. Pointing it at the root is a known failure with a name: the cache reads every top-level
 * directory as a project, so the console's projects screen then walks every artifact blob to count them - a minute a
 * page over a soaked store. {@link ArtifactStore#scope} nests, so the cache's own per-tenant scope composes on top
 * and the layout is {@code <store>/.system/cache/<tenant>/...}.
 *
 * <p><b>It goes in the product's own space, not beside the tenants.</b> The store root holds the tenant scopes, so
 * a space of the product's placed among them is mistakable for a tenant in both directions: a tenant could be
 * created under the same name and write its {@code <tenant>/<repository>/...} spaces inside this one, and every
 * enumeration that derives tenants from store names keeps what merely looks like a name, so the cache would be
 * offered as a tenant with working Open and Delete controls. Under {@link Scopes#SYSTEM} it cannot be either: that
 * name is outside the scope-name grammar, so no tenant can reach it and no enumeration mistakes it - with nothing
 * to keep in step, where a plain {@code cache} would have needed a place on a list of forbidden words and would
 * have been offered as a tenant on the day someone forgot.
 *
 * <p><b>This is a layout change and there is no shim.</b> A filesystem cache used to live at
 * {@code jenreg.cache.root}, defaulting to a relative {@code data} directory; it now lives under the store. An
 * existing deployment's cache is not migrated - it is a cache, so it refills.
 */
public final class DelegatingCacheStorageProvider implements CacheStorageProvider {



    /** The repository's backend selection. The cache has none of its own - that is the point. */
    private static final String STORE_SELECTION = "jenreg.store";

    @Override
    public String name() {
        return "delegating";
    }

    /**
     * Deliberately empty: the artifact-store provider declares what IT needs and
     * {@link ArtifactStoreProvider#resolve} validates it before building anything, naming every missing key.
     * Declaring the same keys again here would be a second list to keep in step.
     */
    @Override
    public Set<String> requiredConfig() {
        return Set.of();
    }

    @Override
    public CacheStorage create(UnaryOperator<String> config) {
        return create(config, ArtifactStoreProvider.resolve(config.apply(STORE_SELECTION), config));
    }

    /** The cache's segment of the store the node hands it - the metered one, so every operation the cache costs
     *  the store is counted where the node counts the rest. */
    @Override
    public CacheStorage create(UnaryOperator<String> config, ArtifactStore store) {
        return new DelegatingCacheStorage(store.scope(Scopes.SYSTEM).scope(Scopes.CACHE));
    }
}
