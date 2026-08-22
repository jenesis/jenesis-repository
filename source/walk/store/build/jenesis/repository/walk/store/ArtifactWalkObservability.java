package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the shared artifact walk: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link StoreArtifactWalk} the deployment {@linkplain
 * StoreArtifactWalk#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class ArtifactWalkObservability implements ObservabilitySource {

    public ArtifactWalkObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return StoreArtifactWalk.installed().map(StoreArtifactWalk::metrics).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return StoreArtifactWalk.installed().map(StoreArtifactWalk::taskStatuses).orElseGet(List::of);
    }
}
