package build.jenesis.repository.proxy;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the proxy revalidating fetcher: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link RevalidatingFetcher} the deployment {@linkplain
 * RevalidatingFetcher#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class RevalidationObservability implements ObservabilitySource {

    public RevalidationObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return RevalidatingFetcher.installed().map(RevalidatingFetcher::metrics).orElseGet(List::of);
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return RevalidatingFetcher.installed().map(RevalidatingFetcher::healthChecks).orElseGet(List::of);
    }
}
