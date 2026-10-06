package build.jenesis.repository.dependents;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The dependents module's storage manifest: it owns the per-repository {@code dependents} space the
 * {@link DeclaredDependents} pass maintains, so the orphan diagnostic and the operator purge know it.
 */
public final class DependentsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(DependentsStore.PREFIX);
    }
}
