/**
 * The closure-source contract kit: the executable {@code ClosureSource} contract and the fixture seam one source
 * registers with.
 *
 * <p>{@code ClosureContract} states each clause the SPI documents once - a release carrying nothing the source reads is
 * no answer from it, what the walk holds is placed and what it does not, or holds for review, is a cut, a closure stops
 * at its bound and says so, a failed store read raises rather than shortening the closure, and nothing is fetched - and
 * {@code ClosureFixture} is how one source publishes the release those checks ask it about. A source is covered by
 * writing a fixture, never by adding the generic assertions to its own suite. Every check asks the source the way the
 * closure pass does, through the pass's own entry point, so a carried source is held to what its document places, not
 * to a placement of its own.
 *
 * <p>Assertion-library-free: a check throws {@link java.lang.AssertionError} naming the source, the property and the
 * expectation. The JUnit driver, the fixtures and the census live under {@code test/closure/contract}. Nothing here
 * provides a service.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.closure.testkit {
    requires transitive build.jenesis.repository.closure;
    requires transitive build.jenesis.repository.closure.spi;
    requires transitive build.jenesis.repository.store;
    requires build.jenesis.repository.compliance.testkit;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store.testkit;
    exports build.jenesis.repository.closure.testkit;
}
