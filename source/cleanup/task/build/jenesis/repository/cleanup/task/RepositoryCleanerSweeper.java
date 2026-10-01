package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionSweeper;

/**
 * The {@link RetentionSweeper} over {@link RepositoryCleaner}: the engine the walk's retention uses, so the on-demand
 * endpoints and the scheduled retention cannot drift.
 */
public final class RepositoryCleanerSweeper implements RetentionSweeper {

    @Override
    public CleanupPlan plan(RepositoryInventory inventory, RetentionPolicy policy, Instant now) throws IOException {
        return new RepositoryCleaner(policy).plan(inventory, now);
    }

    @Override
    public CleanupPlan sweep(RepositoryInventory inventory, RetentionPolicy policy, Instant now) throws IOException {
        return new RepositoryCleaner(policy).sweep(inventory, now);
    }
}
