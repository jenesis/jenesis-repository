package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the pending re-screen ({@link PendingScreenTask}), always scheduled: a repository that admitted nothing
 * through an outage costs one listing a pass. Each repository is screened through the gate its fetches are, built from
 * its own effective settings - the flavour its upstream mark chooses, its advisory sources and its dials - as the
 * migration re-screen builds one ({@link MigrationRescreenTaskProvider#gate}).
 */
public final class PendingScreenTaskProvider implements MaintenanceTaskProvider {

    /** How often copies served through an outage are asked about again: the window a recovered feed leaves open. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("pending-rescreen-interval", "PT15M");

    @Override
    public String name() {
        return PendingScreenTask.NAME;
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        return Optional.of(new PendingScreenTask(INTERVAL.resolve(config),
                repository -> MigrationRescreenTaskProvider.gate(repository, GatePolicyProvider.Path.fetched(repository)),
                PendingScreenTaskProvider::holdDays));
    }

    private static int holdDays(UnaryOperator<String> config) {
        String value = config.apply("immaturity-hold-days");
        if (value == null || value.isBlank()) {
            return 2;   // the CoreSettingsContributor immaturity-hold-days default (the secure floor)
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException malformed) {
            return 2;
        }
    }
}
