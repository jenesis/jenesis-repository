package build.jenesis.repository.metadata.store;

import module java.base;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered {@link ObservabilitySource} for the consolidated metadata store: a thin, ServiceLoader-instantiated
 * adapter that reports the process-wide {@link MetadataMetrics#SHARED} counters. Kept separate from the accumulator
 * so the {@link ServiceLoader} entry has no state of its own and every {@link StoreMetadata} reports into the one
 * shared instance this adapter reads.
 */
public final class MetadataObservability implements ObservabilitySource {

    public MetadataObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return MetadataMetrics.SHARED.metrics();
    }
}
