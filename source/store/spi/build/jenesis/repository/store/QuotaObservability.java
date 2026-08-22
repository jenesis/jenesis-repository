package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the deployment-wide storage quota: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link QuotaArtifactStore} the deployment {@linkplain
 * QuotaArtifactStore#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class QuotaObservability implements ObservabilitySource {

    public QuotaObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return QuotaArtifactStore.installed().map(QuotaArtifactStore::metrics).orElseGet(List::of);
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return QuotaArtifactStore.installed().map(QuotaArtifactStore::healthChecks).orElseGet(List::of);
    }
}
