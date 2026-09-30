/**
 * The search contract: the {@link build.jenesis.repository.search.SearchMode} a repository answers in, and the seam
 * the one search reaches a full-text index through <em>without</em> a compile-time dependency on the module that
 * builds it. A {@link build.jenesis.repository.search.SearchQueryProvider} is discovered with {@code ServiceLoader}
 * and binds a repository's scoped store to a {@link build.jenesis.repository.search.SearchQuery}; a repository whose
 * index is off, not built yet, or not installed answers by name instead. Kept minimal-dependency (only the store
 * SPI): the Lucene index, its sweep and its reader ride in {@code build.jenesis.repository.search.lucene}, and the
 * search both surfaces call in {@code build.jenesis.repository.search.service}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.search {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.search;
    uses build.jenesis.repository.search.SearchQueryProvider;
}
