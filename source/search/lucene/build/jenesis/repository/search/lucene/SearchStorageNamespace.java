package build.jenesis.repository.search.lucene;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The search module's storage manifest: it owns the per-repository {@code index/search} generations the
 * {@link SearchIndex} maintains, so the orphan diagnostic and the explicit operator purge know the key-space
 * without a hardcoded table.
 */
public final class SearchStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(SearchIndex.DIRECTORY);
    }
}
