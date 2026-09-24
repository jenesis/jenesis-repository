package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RetentionSetting;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.gate.QuarantineLog;

/**
 * The scheduled retention over the {@link QuarantineLog}: each repository's gate-decision log is pruned to the
 * {@code quarantine-log-retention} age and the {@code quarantine-log-cap} count, so a busy gate does not append one
 * object per verdict forever. It rides the cleanup pass's enablement and cadence (see
 * {@link QuarantineRetentionTaskProvider}) and reads its two dials live through the pass configuration, so a changed
 * setting applies on the next pass without a restart. Exclusive by default, like every mutating sweep.
 */
public final class QuarantineRetentionTask implements MaintenanceTask {

    /** The age bound on gate-decision log rows: half a year unless configured, and blank, zero or negative disables
     *  age pruning. */
    static final RetentionSetting RETENTION = RetentionSetting.of("quarantine-log-retention", "P180D");

    private final Duration interval;

    public QuarantineRetentionTask(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "quarantine-retention";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        Duration maxAge = RETENTION.resolve(context.config()).orElse(null);
        int maxCount = maxCount(context.config().apply("quarantine-log-cap"));
        if (maxAge == null && maxCount <= 0) {
            return;
        }
        int removed = new QuarantineLog(context.store()).prune(context.now(), maxAge, maxCount);
        context.gauge("jenreg.quarantine.log.pruned",
                "Quarantine-log objects removed by the retention sweep this pass",
                Map.of("repository", context.repository()), removed);
    }

    /** The count bound: unset or unparseable reads as the default (off); zero or negative disables the cap. */
    private static int maxCount(String setting) {
        try {
            return setting == null || setting.isBlank() ? 0 : Integer.parseInt(setting.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
