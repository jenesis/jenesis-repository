package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.OpenPgpVerification;

/** The detached OpenPGP scheme over a Maven-shaped {@code .asc}: a generated publisher key, its armoured keyring as
 *  the material, another publisher's keyring as the foreign material. */
final class OpenPgpDetachedSchemeFixture implements SignatureSchemeFixture {

    private static final String LOCATION = "/maven/com/acme/widget/1.0/widget-1.0.jar.asc";

    private static final byte[] COVERED = "the widget jar's bytes".getBytes(StandardCharsets.UTF_8);

    private static final byte[] TAMPERED = "the widget jar's bytes, altered".getBytes(StandardCharsets.UTF_8);

    private byte[] signature;

    private byte[] keyring;

    private byte[] foreign;

    private SignerIdentity signer;

    @Override
    public String schemeClass() {
        return "build.jenesis.repository.format.signing.OpenPgpDetachedScheme";
    }

    @Override
    public ArtifactSignatures.Scheme declared() {
        return ArtifactSignatures.Scheme.OPENPGP_DETACHED;
    }

    @Override
    public void start() throws Exception {
        OpenPgpSigner publisher = new OpenPgpSigner(OpenPgpSigner.generate("Acme <release@acme.example>",
                Duration.ofDays(365)).secretKey());
        keyring = publisher.publicKeyring();
        signature = publisher.detachedSignature(COVERED, OpenPgpSigner.Encoding.ARMOURED);
        foreign = new OpenPgpSigner(OpenPgpSigner.generate("Other <release@other.example>",
                Duration.ofDays(365)).secretKey()).publicKeyring();
        String keyId = OpenPgpVerification.facts(signature).orElseThrow().keyId();
        signer = SignerIdentity.openpgp(OpenPgpVerification.fingerprint(keyId, keyring).orElseThrow());
    }

    @Override
    public byte[] covered() {
        return COVERED;
    }

    @Override
    public byte[] tampered() {
        return TAMPERED;
    }

    @Override
    public ArtifactSignatures.Evidence evidence() {
        return new ArtifactSignatures.Evidence(declared(), signature, SignatureSchemeFixture.over(COVERED), LOCATION);
    }

    @Override
    public ArtifactSignatures.Evidence unrecognisable() {
        return new ArtifactSignatures.Evidence(declared(), "not a signature".getBytes(StandardCharsets.UTF_8),
                SignatureSchemeFixture.over(COVERED), LOCATION);
    }

    @Override
    public byte[] material() {
        return keyring;
    }

    @Override
    public byte[] foreign() {
        return foreign;
    }

    @Override
    public SignerIdentity signer() {
        return signer;
    }
}
