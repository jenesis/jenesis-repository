package build.jenesis.repository.proxy;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the proxy miss cache: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link NegativeCachingFetcher} the deployment {@linkplain
 * NegativeCachingFetcher#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class NegativeCacheObservability implements ObservabilitySource {

    public NegativeCacheObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return NegativeCachingFetcher.installed().map(NegativeCachingFetcher::metrics).orElseGet(List::of);
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return NegativeCachingFetcher.installed().map(NegativeCachingFetcher::healthChecks).orElseGet(List::of);
    }
}
