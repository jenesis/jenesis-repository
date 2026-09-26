package build.jenesis.repository.health.store;

import module java.base;

import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The health module's storage manifest. Each coordinate's health is the {@code health} section of the consolidated
 * per-coordinate metadata document ({@code meta}, owned by {@code MetadataStorageNamespace}), reclaimed with that
 * document on the coordinate's last-version eviction, so this manifest declares only what lives outside it: the
 * repo-level {@link HealthLedger#scanned health stamp} freshness singleton at {@code health/scanned}, the eviction
 * epoch, and the derived weakest-first rank index. Per-repository (never shared): two tenants never share a
 * coordinate's health.
 */
public final class HealthStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // The freshness stamp singleton, the eviction epoch (bumped when a coordinate's health is reclaimed by its
        // last-version eviction, so the rank index rebuilds on its next pass rather than lingering until the next scan),
        // and the derived weakest-first rank index (a repository-scoped key-space rebuilt and generation-reclaimed by the
        // health rank-index pass, not by coordinate eviction) - declared so the storage-completeness sweep knows all
        // three are owned key-spaces rather than orphans to surface.
        return Set.of(HealthLedger.SCANNED, HealthLedger.EVICTED, HealthRankIndex.PREFIX);
    }
}
