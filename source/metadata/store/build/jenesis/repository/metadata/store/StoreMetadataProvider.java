package build.jenesis.repository.metadata.store;

import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Discovers the store-backed consolidated metadata store: stateless, binding the per-request scoped store on each
 * call, so one instance serves every tenant and repository.
 */
public final class StoreMetadataProvider implements MetadataProvider {

    @Override
    public MetadataStore over(ArtifactStore store) {
        return new StoreMetadata(store);
    }
}
