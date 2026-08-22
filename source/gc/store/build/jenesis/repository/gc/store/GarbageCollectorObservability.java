package build.jenesis.repository.gc.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered observability face of the mark-and-sweep garbage collector: a no-argument {@link ObservabilitySource} the report loads
 * through {@code ServiceLoader}, answering from the live {@link MarkSweepGarbageCollector} the deployment {@linkplain
 * MarkSweepGarbageCollector#install installed} - and nothing at all before one is installed, so a deployment without the feature
 * contributes no signal rather than a healthy-looking empty one.
 */
public final class GarbageCollectorObservability implements ObservabilitySource {

    public GarbageCollectorObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return MarkSweepGarbageCollector.installed().map(MarkSweepGarbageCollector::metrics).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return MarkSweepGarbageCollector.installed().map(MarkSweepGarbageCollector::taskStatuses).orElseGet(List::of);
    }
}
