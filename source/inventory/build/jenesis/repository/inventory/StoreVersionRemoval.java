package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.VersionRemoval;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A client's removal of one version, carried out by the store-backed inventory's eviction
 * ({@link StoreRepositoryInventory#remove}). A version with a published row is removed as that row records it; one
 * without - a pointer whose row a crash never wrote, which the back-fill would restore - is removed all the same,
 * since the pointers are what serves and the eviction reaps whatever rows it finds.
 */
public final class StoreVersionRemoval implements VersionRemoval {

    public StoreVersionRemoval() {
    }

    @Override
    public boolean pinned(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return new StoreRepositoryInventory(store).pinned(ecosystem, coordinate, version);
    }

    @Override
    public void remove(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        if (inventory.pinned(ecosystem, coordinate, version)) {
            return;
        }
        Release release = inventory.release(ecosystem, coordinate, version)
                .orElseGet(() -> new Release(ecosystem, coordinate, version, null, null, false, false));
        inventory.remove(release);
    }
}
