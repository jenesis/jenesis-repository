package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the continuous re-analysis pass. It rides the {@code scheduled-scan} enablement and the
 * {@code scan-interval-millis} cadence of the gauge scan; whether a pass releases is read per pass from
 * {@code kev-auto-release}, so releases can be left to human review without a restart.
 */
public final class ReanalysisTaskProvider implements MaintenanceTaskProvider {

    @Override
    public String name() {
        return "reanalyze";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new ReanalysisTask(IntervalSetting.SCANS.resolve(config),
                AdvisorySource.resolve(config), KnownExploitedSource.resolve(config)));
    }
}
