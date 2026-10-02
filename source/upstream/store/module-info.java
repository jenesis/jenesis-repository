/**
 * The store-backed upstream credentials: it provides
 * {@link build.jenesis.repository.upstream.UpstreamCredentialSourceProvider} answering to {@code store}, keeping each
 * host's header, encrypted, in {@code .system/config/upstream-auth} and serving proxied fetches from an in-memory
 * snapshot. Without this module (or another credential source) a deployment proxies public upstreams only. The
 * document is deployment-global, since an upstream credential serves the deployment's outbound fetches rather than one
 * tenant's data.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.upstream.store {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    exports build.jenesis.repository.upstream.store;
    provides build.jenesis.repository.upstream.UpstreamCredentialSourceProvider
            with build.jenesis.repository.upstream.store.StoreUpstreamCredentialsProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.upstream.store.UpstreamCredentialsStorageNamespace;
}
