package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.RetentionPolicy;

/**
 * Runs a {@link RetentionPolicy} over a {@link RepositoryInventory}: {@link #plan} computes what would be removed,
 * {@link #sweep} computes and applies it, evicting each condemned release through the inventory. The plan is returned
 * either way, for reporting.
 *
 * <p>Both stream the inventory's grouped releases through the policy's {@link RetentionPolicy#planner planner}, so
 * memory is one coordinate's versions plus the plan (sized by what is condemned), and a sweep evicts each group as the
 * enumeration flows: a crash mid-pass has applied every group passed, and the resumed or next pass finishes the rest.
 * The returned plan is what this call condemned - a resumed walk-riding sweep reports its share. The enumeration is the
 * inventory's published rows, one per version with its coordinate, since the shared rebuild pass's unit is the pointer,
 * which carries no coordinate to group by.
 */
public final class RepositoryCleaner {

    private final RetentionPolicy policy;

    public RepositoryCleaner(RetentionPolicy policy) {
        this.policy = policy;
    }

    public CleanupPlan plan(RepositoryInventory inventory, Instant now) throws IOException {
        List<CleanupPlan.Eviction> evictions = new ArrayList<>();
        RetentionPolicy.Planner planner = policy.planner(now, evictions::add);
        inventory.releases(planner::offer);
        planner.finish();
        return new CleanupPlan(List.copyOf(evictions));
    }

    public CleanupPlan sweep(RepositoryInventory inventory, Instant now) throws IOException {
        List<CleanupPlan.Eviction> evictions = new ArrayList<>();
        RetentionPolicy.Planner planner = policy.planner(now, eviction -> {
            inventory.evict(eviction.release());
            evictions.add(eviction);
        });
        inventory.releases(planner::offer);
        planner.finish();
        return new CleanupPlan(List.copyOf(evictions));
    }
}
