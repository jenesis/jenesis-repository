/**
 * The free-text search query contract: the read-side seam the search box (the {@code /api/search} endpoint and the
 * console) reaches an installed search index through <em>without</em> a compile-time dependency on the optional index
 * module. A {@link build.jenesis.repository.search.SearchQueryProvider} is discovered with {@code ServiceLoader} and
 * binds a repository's scoped store to a {@link build.jenesis.repository.search.SearchQuery}; with no provider
 * installed the query degrades - {@code /api/search} falls back to the built-in live substring scan of the
 * published-coordinate pointers - so a deployment without the index module still searches, just without the index.
 * Kept minimal-dependency (only the store SPI) so the core graph stays light: the Lucene index itself, its sweep and
 * its volatile-swap reader ride in the separate {@code build.jenesis.repository.search.lucene} implementation module.
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
