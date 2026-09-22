/**
 * The repository's OpenPGP signing key, shared by every format that signs one of its own documents.
 *
 * <p>It exists because there were two copies of it and a third was about to be written. {@code ReleaseSigner} in the
 * Debian format and {@code RpmMetadataSigner} in the RPM one were separate classes in separate modules, and 130 of
 * the RPM one's 151 lines were byte-identical to the Debian one; a Terraform provider registry signs its
 * {@code SHA256SUMS} the same way again. What differs between them is which document is signed and what the
 * signature file is called, which is a statement about a protocol and stays with the format that speaks it.
 *
 * <p>It also carries the consumer half, {@code OpenPgpVerification}: reading what a third party's detached signature
 * states about itself, and checking it over a streamed body. Signing our own documents and checking someone else's are
 * opposite directions, but they are the same library and the same key handling, and this build may load Bouncy Castle
 * in exactly one module - a second would be a split package that fails the boot layer.
 *
 * <p>A module rather than a class in one of them, because a format plugin depends on its SPI and on shared helpers -
 * never on a sibling plugin, which would make the RPM format's graph depend on whether Debian is installed.
 *
 * <p>And it is where the verifiers are offered to the gate: each scheme this module can check is a
 * {@code SignatureScheme} it provides - detached and clearsigned OpenPGP, PKCS#7, the bare RSA member and the
 * bare signature beside a carried X.509 chain - so the one signature inspector dispatches by discovery and imports
 * no library. That is why this module requires the compliance contract: the scheme is its service, and a format
 * that signs its own documents through {@code OpenPgpSigner} sees nothing of it.
 *
 * @jenesis.release 25
 * @jenesis.alias org.bouncycastle.pg org.bouncycastle/bcpg-jdk18on
 * @jenesis.alias org.bouncycastle.pkix org.bouncycastle/bcpkix-jdk18on
 * @jenesis.alias org.bouncycastle.provider org.bouncycastle/bcprov-jdk18on
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.signing {
    requires transitive build.jenesis.repository.format;
    requires build.jenesis.repository.compliance;
    requires org.bouncycastle.pg;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;
    exports build.jenesis.repository.format.signing;
    provides build.jenesis.repository.compliance.SignatureScheme
            with build.jenesis.repository.format.signing.OpenPgpDetachedScheme,
                    build.jenesis.repository.format.signing.OpenPgpClearsignedScheme,
                    build.jenesis.repository.format.signing.Pkcs7Scheme,
                    build.jenesis.repository.format.signing.RsaDetachedScheme,
                    build.jenesis.repository.format.signing.X509DetachedScheme;
}
