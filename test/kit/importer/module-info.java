/**
 * The import-connector contract kit: the executable {@code ImportSource} / {@code ImportSourceProvider} behavioural
 * contract and the fixture seam one connector registers with.
 *
 * <p>{@code ImportContract} states each documented clause once - an interrupted walk resumes from its own cursor
 * without losing or repeating an asset, an asset is fetched only when opened and copies to storage unread, a refused
 * credential is distinguishable from an absence and from an outage, a connector without what it needs declines rather
 * than half-building, and every reported path is one a store write may address - and {@code ImportFixture} is how one
 * connector scripts the incumbent those checks run against. A connector is covered by writing a fixture, never by
 * adding assertions to its own suite, which is how five connectors arrived at five different answers to the same
 * questions.
 *
 * <p>{@code ScriptedUpstream} is the shared incumbent double the five per-connector {@code FakeFetcher} copies had each
 * re-invented. It is part of the contract rather than a convenience: it records every request so an anonymous walk can
 * be proven to send no credential, and it serves a generated artifact body <em>only</em> through the streaming
 * {@code download} overload, so a connector that reaches for the buffered {@code fetch} fails by name. The streaming
 * tripwire itself is the format kit's {@code GeneratedBody} + {@code WitnessStore}, reused rather than re-implemented.
 *
 * <p>The module depends only on the importer SPI and the format testkit - no junit, no assertion library, no server -
 * so a downstream distribution's test modules can require it for their own connector fixtures exactly as they already
 * require the store testkit. The classes are test doubles; nothing here provides a service, so the module is inert on a
 * runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.importer.testkit {
    requires transitive build.jenesis.repository.importer;
    requires transitive build.jenesis.repository.format.testkit;
    exports build.jenesis.repository.importer.testkit;
    uses build.jenesis.repository.importer.ImportSourceProvider;
}
