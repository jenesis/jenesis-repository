package build.jenesis.repository.dependents;

import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Publishes the reverse-dependency index's read model as the discovered {@link DependentsQueryProvider}, binding a
 * {@link DependentsQueryReader} to the given scoped store - the read path over the shards {@link DependentsIndex}
 * writes, without its build machinery.
 */
public final class DependentsIndexProvider implements DependentsQueryProvider {

    @Override
    public DependentsQuery over(ArtifactStore store) {
        return new DependentsQueryReader(store);
    }
}
