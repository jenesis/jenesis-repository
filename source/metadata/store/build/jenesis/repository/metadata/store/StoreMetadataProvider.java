package build.jenesis.repository.metadata.store;

import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;

/** The store-backed metadata store: stateless, binding the scoped store per call, so one instance serves every
 *  tenant and repository. */
public final class StoreMetadataProvider implements MetadataProvider {

    @Override
    public MetadataStore over(ArtifactStore store) {
        return new StoreMetadata(store);
    }
}
