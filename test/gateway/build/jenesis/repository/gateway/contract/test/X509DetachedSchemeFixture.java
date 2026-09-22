package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.Pkcs7Verification;
import build.jenesis.repository.signing.testkit.Pkcs7Fixtures;

/** The bare-signature-with-carried-chain scheme, a gem's shape: the evidence carries the publisher's chain as its
 *  signer, the material is the authority's certificate, the foreign material another authority's. */
final class X509DetachedSchemeFixture implements SignatureSchemeFixture {

    private static final String LOCATION = "/rubygems/gems/widget-1.0.0.gem!data.tar.gz.sig";

    private static final byte[] COVERED = "the gem's data member".getBytes(StandardCharsets.UTF_8);

    private static final byte[] TAMPERED = "the gem's data member, altered".getBytes(StandardCharsets.UTF_8);

    private byte[] signature;

    private byte[] chain;

    private byte[] anchors;

    private byte[] foreign;

    private SignerIdentity signer;

    @Override
    public String schemeClass() {
        return "build.jenesis.repository.format.signing.X509DetachedScheme";
    }

    @Override
    public ArtifactSignatures.Scheme declared() {
        return ArtifactSignatures.Scheme.X509_DETACHED;
    }

    @Override
    public void start() throws Exception {
        Pkcs7Fixtures.Authority authority = Pkcs7Fixtures.authority("Gem Root");
        Pkcs7Fixtures.Signer publisher = Pkcs7Fixtures.signer(authority, "Gem Publisher", 2048);
        chain = Pkcs7Fixtures.pem(publisher.certificate(), authority.certificate());
        anchors = Pkcs7Fixtures.pem(authority.certificate());
        foreign = Pkcs7Fixtures.pem(Pkcs7Fixtures.authority("Other Root").certificate());
        java.security.Signature signing = java.security.Signature.getInstance("SHA256withRSA");
        signing.initSign(publisher.key());
        signing.update(COVERED);
        signature = signing.sign();
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
        return new ArtifactSignatures.Evidence(declared(), signature, SignatureSchemeFixture.over(COVERED), LOCATION,
                chain);
    }

    @Override
    public ArtifactSignatures.Evidence unrecognisable() {
        return new ArtifactSignatures.Evidence(declared(), signature, SignatureSchemeFixture.over(COVERED), LOCATION,
                "not a certificate chain".getBytes(StandardCharsets.UTF_8));
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
