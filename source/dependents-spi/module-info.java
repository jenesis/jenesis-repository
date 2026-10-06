/**
 * The declared-dependencies ("who declares a dependency on X") query contract: the seam the API, the console panel and
 * the CLI reach the declared dependents through without depending on the module that keeps them - the closure pass,
 * which writes them beside the resolved ones. A discovered
 * {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider} binds a repository's scoped store to a
 * {@link build.jenesis.repository.dependents.spi.DependentsQuery}; without one the declared half says it is not
 * installed. It depends only on the store SPI; the rows and the pass that keeps them live in the implementation.
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
