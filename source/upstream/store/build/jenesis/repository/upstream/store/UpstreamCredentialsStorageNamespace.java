package build.jenesis.repository.upstream.store;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The upstream-credentials module's storage manifest: it owns the {@code config/upstream-auth} document
 * ({@link StoreUpstreamCredentials}). The space is shared (deployment-global) by design - an upstream host's
 * credential authenticates the deployment's outbound proxy fetches, not any one tenant's data - and sits under
 * the superadmin {@code config/} root, the manifest's one legitimate shared home beside {@code auth/}.
 */
public final class UpstreamCredentialsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(StoreUpstreamCredentials.PATH);
    }
}
