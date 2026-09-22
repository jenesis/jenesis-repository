package build.jenesis.repository.staging.store;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RetentionSetting;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled staging reap: each repository's {@link StoreStaging#reap} pass drops abandoned-OPEN stagings (their
 * blobs then fall to the blob GC) and deletes sealed markers past the {@code staging-ttl}, so neither key space
 * grows forever. It rides the cleanup pass's enablement and cadence (see {@link StagingReapTaskProvider}) and reads
 * its TTL live through the pass configuration, so a changed setting applies on the next pass without a restart.
 * Exclusive by default, like every mutating sweep.
 */
public final class StagingReapTask implements MaintenanceTask {

    /** How long an open staging repository may sit untouched before the reap drops it: thirty days unless
     *  configured, and blank, zero or negative disables the reap. */
    static final RetentionSetting TTL = RetentionSetting.of("staging-ttl", "P30D");

    private final Duration interval;

    public StagingReapTask(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "staging-reap";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        Duration ttl = TTL.resolve(context.config()).orElse(null);
        if (ttl == null) {
            return;
        }
        int reaped = new StoreStaging(context.store()).reap(context.now(), ttl);
        context.gauge("jenreg.staging.reaped",
                "Staging ids reaped (abandoned stagings dropped, sealed markers removed) this pass",
                Map.of("repository", context.repository()), reaped);
    }
}
