/**
 * The declared-dependencies ("who declares a dependency on X") query contract: the seam the API, the console panel and
 * the CLI reach the declared-dependencies index through without depending on its optional implementation module. A discovered
 * {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider} binds a repository's scoped store to a
 * {@link build.jenesis.repository.dependents.spi.DependentsQuery}; without one the endpoint answers {@code 501} and the
 * console hides the panel. It depends only on the store SPI; the index, its parser and its sweep live in the
 * implementation.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.dependents.spi {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.dependents.spi;
    uses build.jenesis.repository.dependents.spi.DependentsQueryProvider;
}
