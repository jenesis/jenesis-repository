/**
 * Credential usage tracking as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.KeyUsageTrackerProvider} answering to {@code batching}, accumulating an
 * allowed request's tenant, key hash and source address on a bounded queue off the request path and flushing each
 * credential's count and last use through the authorization store at most once per day. A deployment without this
 * module records no usage and its health surface reports the worker as off.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.usage {
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    exports build.jenesis.repository.usage to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.usage.test;
    provides build.jenesis.repository.server.spi.KeyUsageTrackerProvider
            with build.jenesis.repository.usage.BatchingKeyUsageTrackerProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.usage.KeyUsageObservability;
}
