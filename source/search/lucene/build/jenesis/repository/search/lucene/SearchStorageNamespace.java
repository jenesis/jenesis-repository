package build.jenesis.repository.search.lucene;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The search module's storage manifest: the per-repository {@code index/search} space {@link SearchIndex} maintains.
 */
public final class SearchStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(SearchIndex.DIRECTORY);
    }
}
