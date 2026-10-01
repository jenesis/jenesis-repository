package build.jenesis.repository.findings.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the findings-filter-index pass, on the same {@code scheduled-scan} enablement and
 * {@code scan-interval-millis} cadence as the compliance scan. It needs no live source - it indexes stored findings -
 * so it runs for a records-only deployment too.
 */
public final class FindingsFilterIndexTaskProvider implements MaintenanceTaskProvider {

    @Override
    public String name() {
        return "findings-filter-index";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new FindingsFilterIndexTask(IntervalSetting.SCANS.resolve(config)));
    }
}
