/**
 * The CMS structures a test has to write and the product only ever reads: a certificate authority, signers under
 * it, the attached {@code SignedData} a NuGet package carries and the detached one a Swift archive does. Built
 * with the same BouncyCastle the product verifies with, so a round trip proves the reader against a writer that
 * follows the specification rather than against itself.
 *
 * <p><b>Why it is a kit.</b> It began inside the gateway test module, where nine suites named it unqualified. A
 * JUnit test module is a leaf, so nothing outside that one module could reach it - and the end-to-end suites are
 * exactly what these schemes most need proving in, since what they show is the wiring rather than the
 * cryptography. The alternative to a kit is a second copy, and a second copy of a fixture is how two suites come
 * to disagree about what a valid signature is. {@code build.jenesis.repository.sigstore.testkit} made the same
 * move for the same reason, and this sits beside it rather than inside it because the two need different halves
 * of BouncyCastle and are wanted by different suites.
 *
 * @jenesis.release 25
 * @jenesis.alias org.bouncycastle.pkix org.bouncycastle/bcpkix-jdk18on
 * @jenesis.alias org.bouncycastle.provider org.bouncycastle/bcprov-jdk18on
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.signing.testkit {
    // Test support only; nothing here ships.
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;
    exports build.jenesis.repository.signing.testkit;
}
