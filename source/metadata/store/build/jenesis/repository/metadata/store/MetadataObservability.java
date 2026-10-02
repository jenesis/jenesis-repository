package build.jenesis.repository.metadata.store;

import module java.base;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered {@link ObservabilitySource} for the metadata store, reporting {@link MetadataMetrics#SHARED}. It holds
 * no state, so every {@link StoreMetadata} reports into the one shared instance whatever {@link ServiceLoader}
 * instantiates.
 */
public final class MetadataObservability implements ObservabilitySource {

    public MetadataObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return MetadataMetrics.SHARED.metrics();
    }
}
