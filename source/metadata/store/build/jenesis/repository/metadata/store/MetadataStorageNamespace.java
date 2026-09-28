package build.jenesis.repository.metadata.store;

import module java.base;

import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The metadata module's storage manifest: it owns the per-repository {@code meta} documents the {@link StoreMetadata}
 * store keeps, so the orphan diagnostic and the explicit operator purge know the key-space without a hardcoded
 * table, and a seeded document never surfaces as an orphan under the reconcile completeness sweep.
 *
 * <p><strong>Per-version reclamation is wired.</strong> {@code InventoryEviction} deletes
 * {@code MetadataKey.version(..)} with the artifact it describes, and the {@code @coordinate} document when the last
 * version of a coordinate goes.
 *
 * <p>So {@code meta} growing under churn means no eviction ran - a suite arming {@code jenrepo.gc} without
 * {@code jenrepo.walks}, say, where retention and collection ride the walk and so never run. A reclamation figure
 * taken from a deployment where the reaper never ran measures the reaper's absence.
 */
public final class MetadataStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(MetadataKey.PREFIX);
    }
}
