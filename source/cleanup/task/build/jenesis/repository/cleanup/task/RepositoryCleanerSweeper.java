package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionSweeper;

/**
 * The {@link RetentionSweeper} over the {@link RepositoryCleaner} engine: each call runs the given policy over the
 * inventory - the same engine the scheduled pass uses, so the on-demand endpoints and the sweep cannot drift.
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
