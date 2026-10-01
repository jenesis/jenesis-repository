package build.jenesis.repository.index;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The published-index module's storage manifest: it owns the per-repository {@code index/publish} generations, so the
 * orphan diagnostic and the operator purge know the key space.
 */
public final class PublishedIndexStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(PublishedIndex.PREFIX);
    }
}
