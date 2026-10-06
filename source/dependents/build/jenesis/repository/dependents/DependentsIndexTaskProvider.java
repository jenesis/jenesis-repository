package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the reverse-dependency sweep: enabled by the {@code dependents-index} setting, its cadence from
 * {@code dependents-interval} (default an hour), off unless enabled. The cadence is an {@link IntervalSetting}
 * constant {@link DependentsSettingsContributor} renders, so the catalogue default cannot drift from the code.
 */
public final class DependentsIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the reverse-dependency index is rebuilt; hourly by default. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("dependents-interval", "PT1H");

    @Override
    public String name() {
        return "dependents";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "dependents-index")) {
            return Optional.empty();
        }
        return Optional.of(new DependentsIndexTask(INTERVAL.resolve(config),
                WalkProvider.resolve(config).orElse(null)));
    }
}
