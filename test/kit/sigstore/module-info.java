/**
 * A Sigstore instance small enough to run inside a test: a certificate authority standing in for Fulcio, a
 * transparency log standing in for Rekor, the trusted root that names both, and the writers that produce a v0.3
 * bundle, a PEP 740 attestation and cosign's annotations over given bytes.
 *
 * <p><b>Why it is a kit and not a class beside the suites that use it.</b> It began as a package-private class in
 * the gateway test module, where twelve suites named it unqualified. A JUnit test module is a leaf, so nothing
 * outside that one module could reach it - and the end-to-end suites are exactly what a signature capability most
 * needs proving in, since what they show is the wiring (the module in the image, discovered there, handed the
 * operator's material, serving the sidecar it stored) rather than the cryptography. The alternative to a kit is a
 * second copy, and a second copy of a fixture is how two suites come to disagree about what a valid bundle is.
 *
 * <p>It is its own kit rather than part of the compliance one because minting a chain needs Bouncy Castle, and
 * that kit is deliberately free of it: it is required by contract modules that must stay small, and a dependency
 * added to a kit is added to every consumer of it.
 *
 * <p><b>What it deliberately is not.</b> It is not a Fulcio and not a Rekor: nothing here runs a service, issues a
 * certificate on demand or serves an inclusion proof over HTTP. It writes the documents those services would have
 * produced, so a verifier can be driven over material of exactly the right shape without a container. The suite
 * that needs the real ones drives the official images instead, and says so.
 *
 * @jenesis.release 25
 * @jenesis.alias org.bouncycastle.pkix org.bouncycastle/bcpkix-jdk18on
 * @jenesis.alias org.bouncycastle.provider org.bouncycastle/bcprov-jdk18on
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.sigstore.testkit {
    // Test support only; nothing here ships.
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;
    exports build.jenesis.repository.sigstore.testkit;
}
