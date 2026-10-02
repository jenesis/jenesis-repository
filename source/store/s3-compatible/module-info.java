/**
 * The half of an S3 API backend that does not depend on the service behind it - reading, listing, scanning and paging
 * through the modular AWS SDK client - which the {@code s3} store extends with its version token and conditional
 * writes.
 *
 * <p>Netty 4.2's split codecs require their optional peers without {@code static} ({@code netty-codec-marshalling}
 * wants {@code org.jboss.marshalling}, {@code netty-codec-protobuf} {@code protobuf.javanano}), so a module-path boot
 * layer carrying them fails on the missing module. The SDK pulls netty for an async client this store never uses, so
 * the exclusion below is declared here as well as on the S3 store, since a Maven exclusion drops an artifact only from
 * the path it names.
 *
 * @jenesis.release 25
 * @jenesis.exclude software.amazon.awssdk.services.s3 io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.s3compatible {
    requires build.jenesis.repository.store;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.core;
    exports build.jenesis.repository.store.s3compatible;
}
