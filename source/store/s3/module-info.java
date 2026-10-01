/**
 * The S3-compatible artifact-store backend (AWS S3, GCS through the XML API, MinIO, LocalStack), discovered through
 * {@code provides} and selected with {@code jenrepo.store=s3}. The version token is the ETag, a cross-node
 * compare-and-set ({@code S3ArtifactStore}). The AWS SDK closure is pinned.
 *
 * <p>Netty 4.2's {@code netty-codec-marshalling} and {@code netty-codec-protobuf} require their optional peers
 * ({@code org.jboss.marshalling}, {@code protobuf.javanano}) without {@code static}, failing a boot layer that carries
 * them; the SDK pulls Netty for an async client this store never uses, so the two codecs are excluded.
 *
 * @jenesis.release 25
 * @jenesis.exclude software.amazon.awssdk.services.s3 io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.s3 {
    exports build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.s3compatible;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.core;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.auth;
    // The SDK's HTTP runs over the product's own client, not a URL connection.
    requires build.jenesis.repository.net.http.aws;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.s3.S3ArtifactStoreProvider;
}
