/**
 * The Azure Blob artifact-store backend. azure-storage-blob ships a real Java module, so this is a plain
 * requires; the rest of the Azure SDK closure resolves transitively through Maven and is pinned here. A
 * pure storage provider: it implements the {@code ArtifactStore} SPI and is discovered through
 * {@code provides}, so the server adds it to its module graph at deploy time and selects it with
 * {@code jenreg.store=azure-blob}. The version token is the blob ETag, giving a true
 * cross-node compare-and-set on conditional writes (see {@code AzureArtifactStore}).
 *
 * <p>The SDK's HTTP runs over the product's own client ({@code AzureTransport}), so its Netty client is excluded. A
 * module that requires the blob client itself can still reach Netty through it, and Netty 4.2's
 * {@code netty-codec-marshalling} and {@code netty-codec-protobuf} require optional peers without {@code static}, so
 * a boot layer carrying them fails on the missing module; those two stay excluded for such a module's sake.
 *
 * @jenesis.release 25
 * @jenesis.exclude com.azure.storage.blob com.azure/azure-core-http-netty io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.azure {
    exports build.jenesis.repository.store.azure to build.jenesis.repository.store.azure.test,
            build.jenesis.repository.store.backends.e2e;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    requires com.azure.core;
    requires reactor.core;
    requires build.jenesis.repository.net.http;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.azure.AzureArtifactStoreProvider;
}
