/**
 * The repository's OpenPGP signing key, shared by every format that signs one of its own documents - Debian's
 * {@code Release}, RPM's {@code repomd.xml}, a Terraform registry's {@code SHA256SUMS}; which document and what the
 * signature file is called stay with the format. A module rather than a class in one format, since a format plugin
 * never depends on a sibling plugin.
 *
 * <p>It also carries the consumer half - reading and checking third parties' signatures - because Bouncy Castle may be
 * loaded in one module only; a second would split a package and fail the boot layer. Each scheme it can check is a
 * {@code SignatureScheme} it provides - detached and clearsigned OpenPGP, PKCS#7, the bare RSA member and the bare
 * signature beside a carried X.509 chain - so the signature inspector dispatches by discovery; hence the compliance
 * contract. {@code SigningKeys} keeps the secret key sealed with the deployment's master key, whose cipher lives in the
 * settings module.
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
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.posture;
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
    provides build.jenesis.repository.posture.SafetyAdvisor
            with build.jenesis.repository.format.signing.UnsealedSigningKeysAdvisor;
}
