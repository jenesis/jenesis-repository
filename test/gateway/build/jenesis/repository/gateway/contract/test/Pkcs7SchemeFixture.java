package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.Pkcs7Verification;
import build.jenesis.repository.signing.testkit.Pkcs7Fixtures;

/** The PKCS#7 scheme over a detached CMS signature (Swift's shape): a signer issued by an authority whose
 *  certificate is the material, and another authority's certificate as the foreign material. */
final class Pkcs7SchemeFixture implements SignatureSchemeFixture {

    private static final String LOCATION = "/swift/scopes/acme/widget/1.0.0.zip.sig";

    private static final byte[] COVERED = "the source archive's bytes".getBytes(StandardCharsets.UTF_8);

    private static final byte[] TAMPERED = "the source archive's bytes, altered".getBytes(StandardCharsets.UTF_8);

    private byte[] signature;

    private byte[] anchors;

    private byte[] foreign;

    private SignerIdentity signer;

    @Override
    public String schemeClass() {
        return "build.jenesis.repository.format.signing.Pkcs7Scheme";
    }

    @Override
    public ArtifactSignatures.Scheme declared() {
        return ArtifactSignatures.Scheme.PKCS7;
    }

    @Override
    public void start() throws Exception {
        Pkcs7Fixtures.Authority authority = Pkcs7Fixtures.authority("Acme Root");
        Pkcs7Fixtures.Signer publisher = Pkcs7Fixtures.signer(authority, "Acme Publisher", 2048);
        signature = Pkcs7Fixtures.detached(publisher, COVERED);
        anchors = Pkcs7Fixtures.pem(authority.certificate());
        foreign = Pkcs7Fixtures.pem(Pkcs7Fixtures.authority("Other Root").certificate());
        signer = SignerIdentity.x509(Pkcs7Verification.spkiSha256(publisher.certificate().getPublicKey()));
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
        return new ArtifactSignatures.Evidence(declared(), "not a CMS structure".getBytes(StandardCharsets.UTF_8),
                SignatureSchemeFixture.over(COVERED), LOCATION);
    }

    @Override
    public byte[] material() {
        return anchors;
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
