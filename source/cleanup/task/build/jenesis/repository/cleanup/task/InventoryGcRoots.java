package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.gc.walk.GcRoots;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;

/**
 * The pointer roots, answered from the durable ecosystem record this module keeps.
 *
 * <p>The free answer is the union of {@code publish} and every installed format's lent roots, which is complete for
 * what is installed and blind to what is not. The inventory records every ecosystem it has ever stored, so it can
 * see the case that union cannot: content held for a format nobody has installed any more, whose blobs no root
 * names and which a sweep would therefore delete. Where that is so it refuses, and the collection pass defers
 * rather than reclaiming.
 */
public final class InventoryGcRoots implements GcRoots {

    @Override
    public Known<List<String>> roots(ArtifactStore store) throws IOException {
        return StoreRepositoryInventory.pointerRoots(store);
    }
}
