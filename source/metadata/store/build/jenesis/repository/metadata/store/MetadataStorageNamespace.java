package build.jenesis.repository.metadata.store;

import module java.base;

import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The metadata module's storage manifest: it owns the per-repository {@code meta} documents the {@link StoreMetadata}
 * store keeps, so the orphan diagnostic and the explicit operator purge know the key-space without a hardcoded
 * table. Declaring it now - before any subsystem's data has been cut over into it - is what stops a seeded document
 * from surfacing as an orphan under the reconcile completeness sweep the moment the foundation lands.
 *
 * <p><strong>Per-version reclamation is wired.</strong> This used to say it was still owed to an eviction cutover
 * ticket, and that until then a document was declared-and-retained under the manifest's "absence never deletes"
 * posture. The cutover landed: {@code InventoryEviction} deletes {@code MetadataKey.version(..)} with the artifact
 * it describes, and the {@code @coordinate} document when the last version of a coordinate goes.
 *
 * <p>The note outlived it by long enough to be believed once. A churn measurement showed {@code meta} growing and
 * was read as confirming this paragraph; the growth had the same cause as everything else in that run, which was
 * a suite arming {@code jenreg.gc} without {@code jenreg.walks} - retention and collection ride the walk, so no
 * pass ran at all and nothing was evicted for the meta document to go with. A reclamation figure taken from a
 * deployment where the reaper never ran measures the reaper's absence.
 */
public final class MetadataStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(MetadataKey.PREFIX);
    }
}
