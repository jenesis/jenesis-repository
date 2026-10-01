package build.jenesis.repository.health.store;

import module java.base;

import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The health module's storage manifest. Each coordinate's health is the {@code health} section of its per-coordinate
 * metadata document, owned by {@code MetadataStorageNamespace} and reclaimed with it, so this declares only what lives
 * outside: the {@link HealthLedger#scanned health stamp} at {@code health/scanned}, the eviction epoch and the rank
 * index.
 */
public final class HealthStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // The freshness stamp, the eviction epoch (bumped when a coordinate's last version is evicted, so the rank
        // index rebuilds on its next pass) and the rank index, whose generations its pass reclaims.
        return Set.of(HealthLedger.SCANNED, HealthLedger.EVICTED, HealthRankIndex.PREFIX);
    }
}
