package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.gc.walk.GcRoots;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;

/**
 * The pointer roots, answered from the durable ecosystem record this module keeps. The union of {@code publish} and
 * every installed format's lent roots is complete for what is installed and blind to what is not; the inventory records
 * every ecosystem it ever stored, so it sees content held for a format no longer installed, whose blobs no root names
 * and a sweep would delete. Then it refuses, and the collection defers.
 */
public final class InventoryGcRoots implements GcRoots {

    @Override
    public Known<List<String>> roots(ArtifactStore store) throws IOException {
        return StoreRepositoryInventory.pointerRoots(store);
    }
}
