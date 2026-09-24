package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the scheduled maintainer-health rank-index pass: it rides the same {@code scheduled-scan} enablement and
 * {@code scan-interval-millis} cadence as the health sweep it keeps the index current with. Unlike the sweep it requires
 * no live health source - it indexes the records already durably stored, so it runs for a records-only deployment whose
 * live source is off, and is a cheap no-op when the records have not moved since the last build.
 */
public final class HealthRankIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the compliance sweeps run. Deliberately the same dial the whole scan family reads (scan, kev-
     *  enforce, reanalyze, vulnerability-rank-index, findings-filter-index, health-scan, health-rank-index), so one
     *  operator switch paces every pass over the same feeds; hourly by default, since each pass hits the upstream
     *  feeds. */
    private static final IntervalSetting INTERVAL = IntervalSetting.millis("scan-interval-millis", "PT1H");

    @Override
    public String name() {
        return "health-rank-index";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new HealthRankIndexTask(INTERVAL.resolve(config)));
    }
}
