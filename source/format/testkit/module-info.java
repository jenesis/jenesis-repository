/**
 * The repository-format contract kit: the executable {@code RepositoryFormat} / {@code ProxyFormat} /
 * {@code ArtifactLayout} contract and the fixture seam one format registers with.
 *
 * <p>{@code FormatContract} states each documented clause once - publish serves the exact bytes, a {@code HEAD} is
 * answered from metadata without opening the blob, the shared traversal probe vectors are refused at both the request
 * seam and the coordinate seam, a withheld version leaves every enumeration surface, a proxy leg holds an upstream
 * body to the digest its protocol advertises and streams it, and a generated index is stable enough to revalidate -
 * and {@code FormatFixture} is how one format supplies the realistic corpus those checks run over. A format is covered
 * by writing a fixture, never by copying assertions into another hand-written per-format suite, which is how the four
 * free layouts drifted apart before this kit existed.
 *
 * <p>The supporting doubles are part of the contract rather than conveniences: {@code WitnessStore} is the store
 * decorator that <em>throws</em> when a blob is opened (so HEAD-from-metadata is proven, not asserted) and that trips
 * on an artifact-sized buffered write or on a body the format materialised before handing it to the store (so
 * streaming is proven over a {@code GeneratedBody} that never exists as an array); {@code ContractExchange} is the
 * in-memory {@code FormatExchange} that also records which response overload a format used and revalidates a buffered
 * one the way the servlet dispatcher does; {@code TraversalVectors} holds the probe vectors as plain data so every
 * format is probed with the same list rather than each fixture inventing its own.
 *
 * <p>The module depends only on the format SPI (and through it the store SPI) - no junit, no assertion library, no
 * server - so the downstream distribution's test modules can require it for their own fixtures exactly as they already
 * require the store testkit. The classes are test doubles; nothing here provides a service, so the module is inert on
 * a runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.testkit {
    requires transitive build.jenesis.repository.format;
    exports build.jenesis.repository.format.testkit;
}
