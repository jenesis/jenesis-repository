/**
 * Request rate limiting: a {@link build.jenesis.repository.server.spi.RateLimiterProvider} answering to
 * {@code token-bucket}, metering each key against an in-memory bucket that refills at the requested rate and holds one
 * window's burst. Per process, so each node of a replicated deployment limits independently, which keeps a
 * coordination service off the hot path. A deployment without this module never limits.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.ratelimit {
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    requires com.github.benmanes.caffeine;
    exports build.jenesis.repository.ratelimit;
    provides build.jenesis.repository.server.spi.RateLimiterProvider
            with build.jenesis.repository.ratelimit.TokenBucketRateLimiterProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.ratelimit.RateLimitSettingsContributor;
}
