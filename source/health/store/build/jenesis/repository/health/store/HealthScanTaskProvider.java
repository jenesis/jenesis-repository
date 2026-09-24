package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the scheduled maintainer-health sweep: it rides the same {@code scheduled-scan} enablement and
 * {@code scan-interval-millis} cadence as the advisory scan and re-analysis passes (the sibling sweeps a deployment
 * turns on together), and additionally requires a health source to be enabled - with no {@link HealthSource} resolved
 * (the scorecard module absent or disabled) there is nothing to probe, so this pass builds nothing. The source itself is
 * the discovered maintainer-health plugin, resolved from the same configuration, so this pass names no backend.
 */
public final class HealthScanTaskProvider implements MaintenanceTaskProvider {

    /** How often the compliance sweeps run. Deliberately the same dial the whole scan family reads (scan, kev-
     *  enforce, reanalyze, vulnerability-rank-index, findings-filter-index, health-scan, health-rank-index), so one
     *  operator switch paces every pass over the same feeds; hourly by default, since each pass hits the upstream
     *  feeds. */
    private static final IntervalSetting INTERVAL = IntervalSetting.millis("scan-interval-millis", "PT1H");

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
        return Optional.of(new HealthScanTask(INTERVAL.resolve(config), source));
    }
}
