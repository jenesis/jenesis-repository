package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.RetentionPolicy;

/**
 * Runs a {@link RetentionPolicy} over a {@link RepositoryInventory}. {@link #plan} computes what would be removed
 * (a dry run); {@link #sweep} computes the same plan and applies it, evicting each condemned release through the
 * inventory. The plan is returned either way, so a deployment can report what was removed and why.
 *
 * <p>Both stream the inventory's grouped release enumeration through the policy's one-coordinate-at-a-time
 * {@link RetentionPolicy#planner planner} instead of materialising {@code releases()} into a list: memory is one
 * coordinate's version group plus the returned plan (the report, sized by what the policy condemns, not by what the
 * repository holds), and the sweep evicts each group's condemned versions <em>as the enumeration flows</em> - so a
 * sweep over a walk-riding inventory that crashes mid-pass has already applied every group it passed, and the
 * resumed pass (or, for a coordinate whose group a crash split, the next pass) finishes the rest. The returned plan
 * is what <em>this</em> call condemned - a resumed walk-riding sweep reports its own share of the pass, not the
 * pass total. The enumeration is the inventory's published rows, one per version with its coordinate, on a pass of
 * its own rather than the shared rebuild pass, whose unit is the pointer and which carries no coordinate to group by.
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
