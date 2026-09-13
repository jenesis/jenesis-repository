/**
 * Credential usage tracking as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.KeyUsageTrackerProvider} answering to {@code batching}, accumulating an
 * allowed request's tenant, key hash and source address on a bounded queue off the request path and flushing each
 * credential's count and last use through the authorization store at most once per day. A deployment without this
 * module records no usage and its health surface reports the worker as off.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
