/**
 * The search contract: the {@link build.jenesis.repository.search.SearchMode} a repository answers in, and the seam a
 * full-text index is reached through without depending on the module that builds it. A discovered
 * {@link build.jenesis.repository.search.SearchQueryProvider} binds a repository's scoped store to a
 * {@link build.jenesis.repository.search.SearchQuery}; a repository whose index is off, unbuilt or not installed
 * answers by name. It depends only on the store SPI: the Lucene index, its sweep and its reader are in
 * {@code build.jenesis.repository.search.lucene}, and the search both surfaces call is in
 * {@code build.jenesis.repository.search.service}.
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
