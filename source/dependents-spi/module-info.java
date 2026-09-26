/**
 * The reverse-dependency ("who depends on X") query contract: the read-side seam the query surface (the HTTP API,
 * the console panel and the CLI) reaches the sharded dependents index through <em>without</em> a compile-time
 * dependency on the optional index module. A {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider}
 * is discovered with {@code ServiceLoader} and binds a repository's scoped store to a
 * {@link build.jenesis.repository.dependents.spi.DependentsQuery}; with no provider installed the query degrades -
 * the endpoint answers {@code 501} and the console hides the panel - so a deployment without the index module still
 * boots. Kept minimal-dependency (only the store SPI) so the core graph stays light: the index itself, its parser
 * and its sweep ride in the separate {@code build.jenesis.repository.dependents} implementation module.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.dependents.spi {
    // The shared ceiling the paged default refuses past, applied through one call.
    requires build.jenesis.repository.bounds;
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.dependents.spi;
    uses build.jenesis.repository.dependents.spi.DependentsQueryProvider;
}
