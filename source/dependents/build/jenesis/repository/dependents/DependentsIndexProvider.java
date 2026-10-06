package build.jenesis.repository.dependents;

import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Publishes the declared-dependencies index's read model as the discovered {@link DependentsQueryProvider}, binding a
 * {@link DependentsQueryReader} to the given scoped store - the read path over the shards {@link DeclaredDependents}
 * writes, without its pass.
 */
public final class DependentsIndexProvider implements DependentsQueryProvider {

    @Override
    public DependentsQuery over(ArtifactStore store) {
        return new DependentsQueryReader(store);
    }
}
