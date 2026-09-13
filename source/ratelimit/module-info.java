/**
 * Request rate limiting as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.RateLimiterProvider} answering to {@code token-bucket}, metering each key
 * against an in-memory bucket that refills at the requested rate and holds one window's burst. Per process - in a
 * replicated deployment each node limits independently, the usual cheap trade for keeping a coordination service
 * off the hot path; a coordinated limiter would be another module. A deployment without this module never limits.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
