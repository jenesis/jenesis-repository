package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the scheduled retention pass: enabled by the {@code scheduled-cleanup} setting, its cadence from
 * {@code cleanup-interval} (default an hour). Off unless enabled, so a deployment sweeps nothing until it opts in -
 * the on-demand cleanup endpoint works either way. The pass's retention leg rides the shared artifact walk
 * (resolved through {@link WalkProvider}; the {@code paged-descent} reference implementation ships in every image)
 * for a resumable, segmented enumeration - but unlike a pure rebuild consumer the sweep does not vanish without one:
 * retention is a configured deletion promise, so with no walk installed it still runs, streaming the key tree
 * without the resumable pass (the recorded degrade). The garbage-collection leg is the discovered
 * {@link GarbageCollectorProvider} capability ({@code mark-sweep} collector in the shipped images, riding
 * the same walk): with none resolved the sweep evicts but reclaims nothing - deleting data is never something a
 * deployment gets without opting in - and the capability surfaces say garbage collection is off.
 *
 * <p>The cadence is held as an {@link IntervalSetting} constant and rendered into {@link RetentionSettingsContributor}
 * from it, so the catalogue default cannot drift from the code.
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
        // The collector's dials are validated here, at boot, though the collector runs from the walk: a garbled
        // jenrepo.gc.grace fails the boot ruling by name rather than the first walk that carries the collector.
        GarbageCollectorProvider.resolve(config);
        return Optional.of(new CleanupTask(INTERVAL.resolve(config)));
    }
}
