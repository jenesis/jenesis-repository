package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;

/**
 * The scheduled declared-dependencies pass, keeping each repository's index of who declares a dependency on what
 * current as versions are published and evicted: one pass of {@link DeclaredDependents} per repository, under the
 * {@code dependents} lease since every shard it writes is a compare-and-set.
 */
public final class DependentsIndexTask implements MaintenanceTask {

    private final Duration interval;

    public DependentsIndexTask(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "dependents";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        new DeclaredDependents(context.store()).pass(context.config());
    }
}
