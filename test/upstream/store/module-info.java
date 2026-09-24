/**
 * Issued upstream credentials over a real filesystem store: a token minted when the credential is set and served
 * from memory after, renewed before it lapses, minted once for many concurrent fetches, never written to the store,
 * refused for a host no installed issuer serves, and a fetch failed - never sent bare - when an issue fails. The
 * issuer is a counting fake provided here, so the suite reaches no cloud.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.upstream.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.upstream.store.test {
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.upstream.store;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.upstream.UpstreamTokenIssuer
            with build.jenesis.repository.upstream.store.test.CountingIssuer;
}
