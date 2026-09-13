/**
 * The Azure Blob artifact-store backend. azure-storage-blob ships a real Java module, so this is a plain
 * requires; the rest of the Azure SDK closure resolves transitively through Maven and is pinned here. A
 * pure storage provider: it implements the {@code ArtifactStore} SPI and is discovered through
 * {@code provides}, so the server adds it to its module graph at deploy time and selects it with
 * {@code jenreg.store=azure-blob}. The version token is the blob ETag, giving a true
 * cross-node compare-and-set on conditional writes (see {@code AzureArtifactStore}).
 *
 * <p>Netty 4.2 split {@code netty-codec} into codecs, and {@code netty-codec-marshalling} declares
 * {@code requires org.jboss.marshalling} without {@code static} for a dependency its own POM marks optional, so a
 * module-path boot layer that carries it fails on the missing module (measured 2026-09-13: nine test JVMs).
 * {@code netty-codec-protobuf} has the same shape ({@code protobuf.javanano}). Nothing here marshals or speaks
 * protobuf; the exclusion below drops both codecs from what the Azure HTTP client pulls in.
 *
 * @jenesis.release 25
 * @jenesis.exclude com.azure.storage.blob io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 */
module build.jenesis.repository.store.azure {
    exports build.jenesis.repository.store.azure to build.jenesis.repository.store.azure.test,
            build.jenesis.repository.store.backends.e2e;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.azure.AzureArtifactStoreProvider;
}
