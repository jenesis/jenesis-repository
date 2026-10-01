package build.jenesis.repository.staging.store;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The staging module's storage manifest: the per-repository {@code staging-state} markers {@link StoreStaging} keeps
 * per staging id and the {@code staging-lock} leases its mutations take. Both prefixes come from their composer's
 * constants, so a rename cannot leave this naming a space nothing writes.
 */
public final class StagingStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(StoreStaging.ROOT, StoreStaging.LOCKS);
    }
}
