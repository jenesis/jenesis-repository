package build.jenesis.repository.index;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The published-index module's storage manifest: it owns the per-repository {@code index/publish} generations the
 * {@link PublishedIndex} maintains, so the orphan diagnostic and the explicit operator purge know the key-space
 * without a hardcoded table.
 */
public final class PublishedIndexStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(PublishedIndex.PREFIX);
    }
}
