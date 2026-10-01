package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the health rank-index pass, on the same {@code scheduled-scan} enablement and {@code scan-interval-millis}
 * cadence as the health sweep. It needs no live health source - it indexes stored records - so it runs for a
 * records-only deployment too.
 */
public final class HealthRankIndexTaskProvider implements MaintenanceTaskProvider {

    @Override
    public String name() {
        return "health-rank-index";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new HealthRankIndexTask(IntervalSetting.SCANS.resolve(config)));
    }
}
