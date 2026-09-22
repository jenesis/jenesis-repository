package build.jenesis.repository.cleanup;

import module java.base;

/**
 * Applies a {@link RetentionPolicy} over a {@link RepositoryInventory}: {@link #plan} computes what would be evicted
 * (a dry run), {@link #sweep} computes the same plan and applies it. The engine is supplied by a
 * {@link RetentionProvider} module discovered with {@link ServiceLoader} - with no provider installed, nothing in
 * the system evicts releases, and the retention endpoints and screens say so.
 */
public interface RetentionSweeper {

    /** Compute what the policy would evict right now, without deleting anything. */
    CleanupPlan plan(RepositoryInventory inventory, RetentionPolicy policy, Instant now) throws IOException;

    /** Compute and apply the plan, evicting each condemned release through the inventory; returns the applied plan. */
    CleanupPlan sweep(RepositoryInventory inventory, RetentionPolicy policy, Instant now) throws IOException;
}
