package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The discovered {@link ObservabilitySource} for the budgeted spool store: a thin, ServiceLoader-instantiated adapter
 * that reports the live gauges of the {@linkplain SpoolStore#install(SpoolStore) installed} {@link SpoolStore}. Kept
 * separate from the store (which has no no-arg constructor - it needs a budget) so the {@link ServiceLoader} entry has
 * no state of its own and simply forwards to the one live spool the running gateway holds, exactly as
 * {@code MetadataObservability} forwards to its shared metrics. With no spool store installed - a deployment that never
 * wires the hardening proxy - it contributes nothing, so the overview never lists a spool signal for something that is
 * not running.
 */
public final class SpoolObservability implements ObservabilitySource {

    public SpoolObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return SpoolStore.installed().map(SpoolStore::metrics).orElseGet(List::of);
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return SpoolStore.installed().map(SpoolStore::healthChecks).orElseGet(List::of);
    }
}
