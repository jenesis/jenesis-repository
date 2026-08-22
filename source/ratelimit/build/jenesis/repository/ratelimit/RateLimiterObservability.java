package build.jenesis.repository.ratelimit;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the token-bucket rate limiter: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link TokenBucketRateLimiter} the deployment {@linkplain
 * TokenBucketRateLimiter#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class RateLimiterObservability implements ObservabilitySource {

    public RateLimiterObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return TokenBucketRateLimiter.installed().map(TokenBucketRateLimiter::metrics).orElseGet(List::of);
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return TokenBucketRateLimiter.installed().map(TokenBucketRateLimiter::healthChecks).orElseGet(List::of);
    }
}
