package build.jenesis.repository.staging.store;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The staging module's storage manifest: it owns the per-repository {@code staging-state} markers
 * {@link StoreStaging} keeps for each staging id and the {@code staging-lock} single-writer leases its
 * request-driven mutations take, so the orphan diagnostic and the explicit operator purge know the key-space without
 * a hardcoded table.
 *
 * <p>Both prefixes are named from their composer's constant, never re-spelled: a manifest that spells a space
 * itself is a second spelling, and a rename in the class that composes the keys would leave this declaration naming a
 * space nothing writes - so the purge's dry run reports an empty blast radius and reclaims nothing.
 */
public final class StagingStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(StoreStaging.ROOT, StoreStaging.LOCKS);
    }
}
