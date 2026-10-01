package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the quarantine-log retention sweep. It rides the retention pass's own enablement and cadence - the
 * {@code scheduled-cleanup} setting turns the storage sweeps on and {@code cleanup-interval} paces them - so an
 * operator who enables scheduled cleanup gets every stop-the-growth reaper with the one switch, and a deployment
 * that never opts in sweeps nothing. The log's own dials ({@code quarantine-log-retention},
 * {@code quarantine-log-cap}) are read live per pass.
 */
public final class QuarantineRetentionTaskProvider implements MaintenanceTaskProvider {

    /** The reapers' shared cadence. */
    private static final IntervalSetting INTERVAL = IntervalSetting.CLEANUP;

    @Override
    public String name() {
        return "quarantine-retention";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-cleanup")) {
            return Optional.empty();
        }
        return Optional.of(new QuarantineRetentionTask(INTERVAL.resolve(config)));
    }
}
