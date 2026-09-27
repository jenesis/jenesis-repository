package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the retroactive known-exploited enforcement pass, beside the gauge {@link VulnerabilityScanTaskProvider}
 * in the same module. It rides the same enablement as the gauge scan - the {@code scheduled-scan} setting turns
 * scheduled re-scanning on and the {@code scan-interval-millis} cadence paces both passes over the same feeds - so a
 * deployment that schedules re-scanning gets retroactive KEV holding with it. Whether a pass actually writes holds is
 * a separate, per-pass decision read inside the task from {@code kev-auto-hold} (default on), so an operator can leave
 * re-scanning on for the gauges yet turn enforcement off without a restart. The feeds are the discovered advisory and
 * known-exploited plugins, resolved from the same configuration, so this pass names no backend.
 */
public final class KevEnforceTaskProvider implements MaintenanceTaskProvider {

    @Override
    public String name() {
        return "kev-enforce";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-scan")) {
            return Optional.empty();
        }
        return Optional.of(new KevEnforceTask(IntervalSetting.SCANS.resolve(config),
                AdvisorySource.resolve(config), KnownExploitedSource.resolve(config)));
    }
}
