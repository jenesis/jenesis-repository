package build.jenesis.repository.staging.store;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the staging reap. It rides the retention pass's own enablement and cadence - the
 * {@code scheduled-cleanup} setting turns the storage sweeps on and {@code cleanup-interval} paces them - so an
 * operator who enables scheduled cleanup gets every stop-the-growth reaper with the one switch, and a deployment
 * that never opts in sweeps nothing. The reap's own TTL ({@code staging-ttl}) is read live per pass.
 */
public final class StagingReapTaskProvider implements MaintenanceTaskProvider {

    /** How often the storage sweeps run. Deliberately the same dial five reapers read (cleanup, quarantine-retention,
     *  build-scan-retention, staging-reap, test-history-retention), so one operator switch paces the whole retention
     *  family; the shared default is stated here in each of them. */
    private static final IntervalSetting INTERVAL = IntervalSetting.of("cleanup-interval", "PT1H");

    @Override
    public String name() {
        return "staging-reap";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Features.enabled(config, "scheduled-cleanup")) {
            return Optional.empty();
        }
        return Optional.of(new StagingReapTask(INTERVAL.resolve(config)));
    }
}
