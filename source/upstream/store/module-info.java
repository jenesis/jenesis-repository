/**
 * The store-backed upstream credentials as a plugin module: it provides
 * {@link build.jenesis.repository.upstream.UpstreamCredentialSourceProvider} answering to {@code store}, keeping
 * each host's ready-to-send header in {@code config/upstream-auth} and serving proxied fetches from an in-memory
 * snapshot. A deployment without this module (or another credential source) proxies public upstreams only. The
 * credential document is deployment-global by design (an upstream credential serves the deployment's outbound
 * fetches, not one tenant's data), so the module's storage manifest declares it shared under the superadmin
 * {@code config/} root.
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
    exports build.jenesis.repository.upstream.store to build.jenesis.repository.server.kernel.test,
            build.jenesis.repository.upstream.store.test,
            build.jenesis.repository.server.kernel.contract.test;
    provides build.jenesis.repository.upstream.UpstreamCredentialSourceProvider
            with build.jenesis.repository.upstream.store.StoreUpstreamCredentialsProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.upstream.store.UpstreamCredentialsStorageNamespace;
}
