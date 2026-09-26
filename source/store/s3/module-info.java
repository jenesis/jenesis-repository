/**
 * The S3-compatible artifact-store backend (AWS S3, GCS via the XML API, MinIO, LocalStack). A pure
 * storage provider: it implements the {@code ArtifactStore} SPI and is discovered through {@code provides},
 * so the server adds it to its module graph at deploy time and selects it with
 * {@code jenreg.store=s3}, with no compile-time dependency from the server. The version token
 * is the object ETag, giving a true cross-node compare-and-set on conditional writes (see
 * {@code S3ArtifactStore}). The rest of the AWS SDK closure resolves transitively through Maven (no
 * exclusions are possible under the module resolver, so the full closure is pulled and pinned here).
 *
 * <p>Netty 4.2 split {@code netty-codec} into codecs whose descriptors require their optional peers without
 * {@code static} - {@code netty-codec-marshalling} wants {@code org.jboss.marshalling}, {@code netty-codec-protobuf}
 * wants {@code protobuf.javanano} - so a module-path boot layer carrying them fails on the missing module (measured
 * 2026-09-13: twenty-one test JVMs through this store's closure). The S3 SDK pulls netty for its async client,
 * which this store never uses; the exclusion below drops the two codecs from what it brings in.
 *
 * @jenesis.release 25
 * @jenesis.exclude software.amazon.awssdk.services.s3 io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.s3 {
    exports build.jenesis.repository.store.s3 to build.jenesis.repository.store.s3.test,
            build.jenesis.repository.store.backends.e2e;
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
