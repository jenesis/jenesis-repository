package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.store.StoredCounter;

/**
 * The scheduled reverse-dependency pass, keeping each repository's {@link DependentsIndex} current as artifacts are
 * published and evicted. With the shared walk installed the rebuild rides a segmented {@code walks/dependents} pass
 * and the task takes no lease: every replica may run it, the walk's per-segment claims keeping them on disjoint
 * ranges. Without a walk it is the exclusive complete recompute under the {@code dependents} lease.
 *
 * <p>In steady state it applies only the blobs the {@link DependentsPublicationObserver} marked
 * ({@link DependentsIndex#applyIncremental}). The full {@link DependentsIndex#rebuild()} runs for the bootstrap (no
 * index yet), the safety valve ({@code dependents-incremental=false}), and every {@code dependents-reconcile-passes}
 * sweeps when that is set; otherwise the walk's {@link DependentsRebuildConsumer} reconciles. Each sweep then runs one
 * pass of the declared tier ({@link DeclaredDependents}).
 */
public final class DependentsIndexTask implements MaintenanceTask {

    /** Incremental sweeps between full reconciles when {@code dependents-reconcile-passes} is unset: never, since the
     *  walk's {@link DependentsRebuildConsumer} reconciles. */
    private static final int DEFAULT_RECONCILE_PASSES = 0;

    private final Duration interval;
    private final ArtifactWalk walk;

    public DependentsIndexTask(Duration interval) {
        this(interval, null);
    }

    public DependentsIndexTask(Duration interval, ArtifactWalk walk) {
        this.interval = interval;
        this.walk = walk;
    }

    @Override
    public String name() {
        return "dependents";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** {@link MaintenanceTask.Exclusion#LEASE} only walk-less: with the walk, per-segment claims keep replicas on
     *  disjoint ranges, every shard commit is compare-and-set and the merge idempotent. */
    @Override
    public Exclusion exclusion() {
        return walk == null ? Exclusion.LEASE : Exclusion.WALK_CLAIM;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        ArtifactStore store = context.store();
        DependentsIndex index = new DependentsIndex(store, walk);
        boolean incrementalOn = !"false".equalsIgnoreCase(context.config().apply("dependents-incremental"));
        // Only what changed, unless a reconcile is due, the safety valve is set or no index is built yet.
        StoredCounter passes = new StoredCounter(store, DependentsStore.PASSES);
        int cadence = cadence(context);
        if (incrementalOn && (cadence == 0 || passes.read() + 1 < cadence) && index.applyIncremental()) {
            passes.add(1);
        } else {
            index.reconcile();
            passes.set(0);
        }
        new DeclaredDependents(store).pass(context.config());
    }

    private static int cadence(RepositoryContext context) {
        String value = context.config().apply("dependents-reconcile-passes");
        if (value == null || value.isBlank()) {
            return DEFAULT_RECONCILE_PASSES;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException unused) {
            return DEFAULT_RECONCILE_PASSES;
        }
    }

}
