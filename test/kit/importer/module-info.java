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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.importer.testkit {
    requires transitive build.jenesis.repository.importer;
    requires transitive build.jenesis.repository.format.testkit;
    exports build.jenesis.repository.importer.testkit;
    uses build.jenesis.repository.importer.ImportSourceProvider;
}
