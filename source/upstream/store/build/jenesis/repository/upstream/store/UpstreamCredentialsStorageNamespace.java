package build.jenesis.repository.upstream.store;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * Declares the deployment-global {@code .system/config/upstream-auth} document ({@link StoreUpstreamCredentials}) as a
 * shared space, since an upstream credential authenticates the deployment's outbound fetches, not one tenant's data.
 */
public final class UpstreamCredentialsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(StoreUpstreamCredentials.PATH);
    }
}
