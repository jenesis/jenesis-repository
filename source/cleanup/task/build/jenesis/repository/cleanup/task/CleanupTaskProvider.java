package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the scheduled cleanup pass: enabled by {@code scheduled-cleanup}, its cadence from {@code cleanup-interval}
 * (an hour by default). Off unless enabled, so a deployment reaps nothing until it opts in; the on-demand cleanup
 * endpoint works either way. The cadence is an {@link IntervalSetting} constant that
 * {@link RetentionSettingsContributor} renders, so the catalogue cannot drift from the code.
 */
public final class CleanupTaskProvider implements MaintenanceTaskProvider {

    /** The reapers' shared cadence. */
    static final IntervalSetting INTERVAL = IntervalSetting.CLEANUP;

    @Override
    public String name() {
        return "cleanup";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-cleanup")) {
            return Optional.empty();
        }
        // The collector runs from the walk, but its dials are validated here at boot, so a garbled jenrepo.gc.grace
        // fails the boot by name rather than the first walk carrying the collector.
        GarbageCollectorProvider.resolve(config);
        return Optional.of(new CleanupTask(INTERVAL.resolve(config)));
    }
}
