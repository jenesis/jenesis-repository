package build.jenesis.repository.usage;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the credential usage tracker: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link BatchingKeyUsageTracker} the deployment {@linkplain
 * BatchingKeyUsageTracker#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class KeyUsageObservability implements ObservabilitySource {

    public KeyUsageObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return BatchingKeyUsageTracker.installed().map(BatchingKeyUsageTracker::metrics).orElseGet(List::of);
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return BatchingKeyUsageTracker.installed().map(BatchingKeyUsageTracker::healthChecks).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return BatchingKeyUsageTracker.installed().map(BatchingKeyUsageTracker::taskStatuses).orElseGet(List::of);
    }
}
