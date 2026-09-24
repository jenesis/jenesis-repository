package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the continuous re-analysis (auto-release) pass, beside the gauge {@link VulnerabilityScanTaskProvider} and
 * the retroactive-hold {@link KevEnforceTaskProvider} in the same module. It rides the same enablement as both - the
 * {@code scheduled-scan} setting turns scheduled re-scanning on and {@code scan-interval-millis} paces all three passes
 * over the same feeds - so a deployment that schedules re-scanning gets self-healing auto-release with it: a hold the
 * enforcement pass wrote is walked back the moment its known-exploited intel clears. Whether a pass actually releases is
 * a separate, per-pass decision read inside the task from {@code kev-auto-release} (default on, the mirror of
 * {@code kev-auto-hold}), so an operator can keep re-scanning on for the gauges and holds yet leave releases to human
 * review without a restart. The feeds are the discovered advisory and known-exploited plugins, resolved from the same
 * configuration, so this pass names no backend.
 */
public final class ReanalysisTaskProvider implements MaintenanceTaskProvider {

    /** How often the compliance sweeps run. Deliberately the same dial the whole scan family reads (scan, kev-
     *  enforce, reanalyze, vulnerability-rank-index, findings-filter-index, health-scan, health-rank-index), so one
     *  operator switch paces every pass over the same feeds; hourly by default, since each pass hits the upstream
     *  feeds. */
    private static final IntervalSetting INTERVAL = IntervalSetting.millis("scan-interval-millis", "PT1H");

    @Override
    public String name() {
        return "reanalyze";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new ReanalysisTask(INTERVAL.resolve(config),
                AdvisorySource.resolve(config), KnownExploitedSource.resolve(config)));
    }
}
