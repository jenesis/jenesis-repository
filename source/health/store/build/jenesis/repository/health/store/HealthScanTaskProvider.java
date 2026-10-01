package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the scheduled maintainer-health sweep, on the same {@code scheduled-scan} enablement and
 * {@code scan-interval-millis} cadence as the advisory scan. It also needs an enabled {@link HealthSource}, the
 * discovered maintainer-health plugin; with none there is nothing to probe and no task.
 */
public final class HealthScanTaskProvider implements MaintenanceTaskProvider {

    @Override
    public String name() {
        return "health-scan";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        HealthSource source = HealthSource.resolve(config);
        if (source == HealthSource.none()) {
            return Optional.empty();                            // no health source enabled: nothing to sweep
        }
        return Optional.of(new HealthScanTask(IntervalSetting.SCANS.resolve(config), source));
    }
}
