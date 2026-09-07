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
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.testkit {
    requires transitive build.jenesis.repository.format;
    exports build.jenesis.repository.format.testkit;
}
