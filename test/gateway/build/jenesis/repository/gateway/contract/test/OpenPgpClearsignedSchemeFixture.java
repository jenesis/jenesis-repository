package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.OpenPgpVerification;

/** The clearsigned OpenPGP scheme over a Helm provenance: the chart archive is the covered bytes, the {@code .prov}
 *  names it by digest, and a detached signature in the document's place is what the scheme must refuse. */
final class OpenPgpClearsignedSchemeFixture implements SignatureSchemeFixture {

    private static final String LOCATION = "/helm/charts/" + HelmFixtures.FILE + ".prov";

    private byte[] covered;

    private byte[] tampered;

    private byte[] provenance;

    private byte[] detached;

    private byte[] keyring;

    private byte[] foreign;

    private SignerIdentity signer;

    @Override
    public String schemeClass() {
        return "build.jenesis.repository.format.signing.OpenPgpClearsignedScheme";
    }

    @Override
    public ArtifactSignatures.Scheme declared() {
        return ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED;
    }

    @Override
    public void start() throws Exception {
        OpenPgpSigner publisher = new OpenPgpSigner(OpenPgpSigner.generate("Charts <charts@acme.example>",
                Duration.ofDays(365)).secretKey());
        keyring = publisher.publicKeyring();
        covered = HelmFixtures.chart(HelmFixtures.NAME, HelmFixtures.VERSION);
        tampered = HelmFixtures.chart(HelmFixtures.NAME, "1.0.1");
        provenance = HelmFixtures.provenance(publisher, HelmFixtures.NAME, HelmFixtures.VERSION, covered);
        detached = publisher.detachedSignature(covered, OpenPgpSigner.Encoding.ARMOURED);
        foreign = new OpenPgpSigner(OpenPgpSigner.generate("Other <charts@other.example>",
                Duration.ofDays(365)).secretKey()).publicKeyring();
        String keyId = OpenPgpVerification.facts(provenance).orElseThrow().keyId();
        signer = SignerIdentity.openpgp(OpenPgpVerification.fingerprint(keyId, keyring).orElseThrow());
    }

    @Override
    public byte[] covered() {
        return covered;
    }

    @Override
    public byte[] tampered() {
        return tampered;
    }

    @Override
    public ArtifactSignatures.Evidence evidence() {
        return new ArtifactSignatures.Evidence(declared(), provenance, SignatureSchemeFixture.over(covered), LOCATION);
    }

    @Override
    public ArtifactSignatures.Evidence unrecognisable() {
        // A detached signature is OpenPGP, and carries no cleartext: not a clearsigned document.
        return new ArtifactSignatures.Evidence(declared(), detached, SignatureSchemeFixture.over(covered), LOCATION);
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
