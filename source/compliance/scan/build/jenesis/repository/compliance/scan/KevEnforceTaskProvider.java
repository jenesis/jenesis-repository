package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the retroactive known-exploited enforcement pass. It rides the {@code scheduled-scan} enablement and the
 * {@code scan-interval-millis} cadence of the gauge scan; whether a pass writes holds is read per pass from
 * {@code kev-auto-hold}, so enforcement can be switched off without a restart. The feeds are the discovered advisory
 * and known-exploited plugins.
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
