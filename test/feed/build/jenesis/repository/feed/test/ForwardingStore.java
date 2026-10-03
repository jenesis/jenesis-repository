package build.jenesis.repository.feed.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;

/**
 * A store decorator the snapshot tests bend one method of at a time: to prove the feed client never scopes a store
 * itself (it is handed one already scoped), and to inject the crash between the snapshot body write and the pointer
 * compare-and-set.
 */
class ForwardingStore extends ForwardingArtifactStore {
    ForwardingStore(ArtifactStore delegate) {
        super(delegate);
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return delegate.scope(tenant);
    }
}
