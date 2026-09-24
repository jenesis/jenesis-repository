package build.jenesis.repository.findings.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the scheduled findings-filter-index pass: it rides the same {@code scheduled-scan} enablement and
 * {@code scan-interval-millis} cadence as the compliance scan whose findings it keeps the index current with. Unlike the
 * scan it needs no live source - it indexes the findings already durably stored, so it runs for a records-only
 * deployment whose live scan is off, and is a cheap no-op when the findings have not moved since the last build.
 */
public final class FindingsFilterIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the compliance sweeps run. Deliberately the same dial the whole scan family reads (scan, kev-
     *  enforce, reanalyze, vulnerability-rank-index, findings-filter-index, health-scan, health-rank-index), so one
     *  operator switch paces every pass over the same feeds; hourly by default, since each pass hits the upstream
     *  feeds. */
    private static final IntervalSetting INTERVAL = IntervalSetting.millis("scan-interval-millis", "PT1H");

    @Override
    public String name() {
        return "findings-filter-index";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new FindingsFilterIndexTask(INTERVAL.resolve(config)));
    }
}
