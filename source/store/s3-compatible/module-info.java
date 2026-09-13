/**
 * The listing half of the S3-compatible object-store backends, shared by {@code s3} and {@code gcs}.
 *
 * <p>Both speak the same S3 API through the same modular AWS SDK client; they differ only in the version token and
 * in how a conditional write is expressed. This module owns what they share so a fix to paging, scanning or
 * reading lands in both at once.
 *
 * <p>Netty 4.2 split {@code netty-codec} into codecs whose descriptors require their optional peers without
 * {@code static} ({@code netty-codec-marshalling} wants {@code org.jboss.marshalling}, {@code netty-codec-protobuf}
 * {@code protobuf.javanano}), so a module-path boot layer carrying them fails on the missing module. The S3 SDK pulls
 * netty for its async client, which this store never uses, and this module's flattened POM is where every consumer
 * of the S3 stores meets that closure - so the exclusion below is declared here as well as on the S3 store, since a
 * Maven exclusion drops an artifact only from the path it names (measured 2026-09-13).
 *
 * @jenesis.release 25
 * @jenesis.exclude software.amazon.awssdk.services.s3 io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 */
module build.jenesis.repository.store.s3compatible {
    requires build.jenesis.repository.store;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.core;
    exports build.jenesis.repository.store.s3compatible;
}
