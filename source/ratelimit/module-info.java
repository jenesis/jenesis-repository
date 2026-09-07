/**
 * Request rate limiting as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.RateLimiterProvider} answering to {@code token-bucket}, metering each key
 * against an in-memory bucket that refills at the requested rate and holds one window's burst. Per process - in a
 * replicated deployment each node limits independently, the usual cheap trade for keeping a coordination service
 * off the hot path; a coordinated limiter would be another module. A deployment without this module never limits.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.ratelimit {
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    exports build.jenesis.repository.ratelimit to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.ratelimit.test;
    provides build.jenesis.repository.server.spi.RateLimiterProvider
            with build.jenesis.repository.ratelimit.TokenBucketRateLimiterProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.ratelimit.RateLimiterObservability;
}
