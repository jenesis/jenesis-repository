package build.jenesis.repository.health.store;

import module java.base;

import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.metadata.MetadataProvider;

/**
 * The health module's storage manifest.
 *
 * <p><strong>Records cut over into the {@code @coordinate} document.</strong> Each coordinate's health now
 * lives in the {@code health} section of the consolidated per-coordinate metadata document ({@code meta}, owned by
 * {@code MetadataStorageNamespace}), so this manifest no longer declares the whole {@code health/} prefix - only the
 * repo-level {@link build.jenesis.repository.health.HealthLedger#scanned health stamp} freshness singleton at {@code health/scanned}, which
 * stays put as a per-repository stamp. Dropping the record prefix is deliberate <em>while that module is installed</em>,
 * mirroring {@code licenses/}: a {@code health/<eco>/<coord>} record left behind after the cutover surfaces as an
 * <em>orphan</em> under the reconcile completeness sweep rather than hiding under a manifest entry, and reclaiming it
 * is the operator's explicit purge, while the section that replaced it rides the {@code meta}
 * key-space's own eviction (the inventory's {@code evict} deleting the {@code @coordinate} document on the last-version
 * eviction). Per-repository (never shared): two tenants never share a coordinate's health.
 *
 * <p><b>That reasoning does not survive the deployment which never installs the module at all.</b> There the
 * same prefix is not residue - {@link StoreHealthLedger} takes its graceful-absence layout and keeps both reads and
 * writes on the {@code health/} sidecar, with a documented point read and a repository-wide scan over it - so the
 * records are the deployment's live health ledger. Reported as orphans they read as reclaimable, which is an
 * invitation to purge live data. The manifest therefore claims the prefix exactly when
 * {@link MetadataProvider#installed()} is empty, switching on the same seam {@code StoreHealthLedger} switches its own
 * layout on, so the two can never disagree about which layout is in force. The distinction that matters is not who
 * wrote the key but whether anything can still read it.
 */
public final class HealthStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // The freshness stamp singleton, the eviction epoch (bumped when a coordinate's health is reclaimed by its
        // last-version eviction, so the rank index rebuilds on its next pass rather than lingering until the next scan),
        // and the derived weakest-first rank index (a repository-scoped key-space rebuilt and generation-reclaimed by the
        // health rank-index pass, not by coordinate eviction) - declared so the storage-completeness sweep knows all
        // three are owned key-spaces rather than orphans to surface.
        if (MetadataProvider.installed().isPresent()) {
            return Set.of(HealthLedger.SCANNED, HealthLedger.EVICTED, HealthRankIndex.PREFIX);
        }
        // With no metadata persistence module installed, StoreHealthLedger takes its graceful-absence layout and both
        // reads AND writes stay on the health/<eco>/<coord> sidecar - a point read for one coordinate and a
        // repository-wide scan over the same subtree for the ledger walk, so this claims that root too - conditional
        // for the reason StorageNamespace states.
        return Set.of(HealthLedger.SCANNED, HealthLedger.EVICTED, HealthRankIndex.PREFIX, HealthLedger.PREFIX);
    }
}
