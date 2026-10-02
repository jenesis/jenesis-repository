package build.jenesis.repository.metadata.store;

import module java.base;

import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * Declares the per-repository {@code meta} documents {@link StoreMetadata} keeps, so the orphan diagnostic and the
 * operator purge know the key-space and a document never surfaces as an orphan.
 *
 * <p>{@code InventoryEviction} deletes a version's document with the artifact it describes, and the coordinate's
 * document with its last version, so {@code meta} growing under churn means no eviction ran.
 */
public final class MetadataStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(MetadataKey.PREFIX);
    }
}
