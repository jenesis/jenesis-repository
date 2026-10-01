/**
 * The Azure Blob artifact-store backend. {@code azure-storage-blob} ships a real Java module, so it is a plain
 * requires, and its closure is pinned. Discovered through {@code provides} and selected with
 * {@code jenrepo.store=azure-blob}; the version token is the blob ETag, a cross-node compare-and-set
 * ({@code AzureArtifactStore}).
 *
 * <p>The SDK's HTTP runs over the product's own client ({@code AzureTransport}), so its Netty client is excluded. A
 * module requiring the blob client can still reach Netty through it, and Netty 4.2's {@code netty-codec-marshalling}
 * and {@code netty-codec-protobuf} require optional peers without {@code static}, failing a boot layer that carries
 * them, so those two stay excluded.
 *
 * @jenesis.release 25
 * @jenesis.exclude com.azure.storage.blob com.azure/azure-core-http-netty io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.azure {
    exports build.jenesis.repository.store.azure;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    requires com.azure.core;
    requires reactor.core;
    requires build.jenesis.repository.net.http;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.azure.AzureArtifactStoreProvider;
}
