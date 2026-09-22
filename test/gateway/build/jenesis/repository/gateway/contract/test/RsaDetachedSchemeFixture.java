package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;
import build.jenesis.repository.format.ArtifactSignatures;

/** The bare RSA scheme over an apk's {@code .SIGN.RSA256.<keyfile>} member: the signer is the key file the member
 *  names, the material a public-key bundle naming that file, the foreign material a bundle naming another. */
final class RsaDetachedSchemeFixture implements SignatureSchemeFixture {

    private static final String ARCHIVE = "/apk/x86_64/widget-1.0-r0.apk";

    private static final String KEY_FILE = "acme.rsa.pub";

    private static final byte[] COVERED = "the control segment's bytes".getBytes(StandardCharsets.UTF_8);

    private static final byte[] TAMPERED = "the control segment's bytes, altered".getBytes(StandardCharsets.UTF_8);

    private byte[] signature;

    private byte[] keys;

    private byte[] foreign;

    @Override
    public String schemeClass() {
        return "build.jenesis.repository.format.signing.RsaDetachedScheme";
    }

    @Override
    public ArtifactSignatures.Scheme declared() {
        return ArtifactSignatures.Scheme.RSA_DETACHED;
    }

    @Override
    public void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair publisher = generator.generateKeyPair();
        KeyPair other = generator.generateKeyPair();
        java.security.Signature signing = java.security.Signature.getInstance("SHA256withRSA");
        signing.initSign(publisher.getPrivate());
        signing.update(COVERED);
        signature = signing.sign();
        keys = bundle(KEY_FILE, publisher.getPublic());
        foreign = bundle("other.rsa.pub", other.getPublic());
    }

    /** A public-key bundle in the shape the {@code signature-trusted-public-keys} setting holds: the key file's
     *  name as a comment line, then the PEM. */
    private static byte[] bundle(String name, PublicKey key) {
        return ("# " + name + "\n-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.US_ASCII);
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
        return new ArtifactSignatures.Evidence(declared(), signature, SignatureSchemeFixture.over(COVERED),
                ARCHIVE + "!.SIGN.RSA256." + KEY_FILE);
    }

    @Override
    public ArtifactSignatures.Evidence unrecognisable() {
        // The same bytes at a member that names no key file and no digest: a bare signature states nothing about
        // itself, so its location is the only thing that can identify it.
        return new ArtifactSignatures.Evidence(declared(), signature, SignatureSchemeFixture.over(COVERED),
                ARCHIVE + "!.PKGINFO");
    }

    @Override
    public byte[] material() {
        return keys;
    }

    @Override
    public byte[] foreign() {
        return foreign;
    }

    @Override
    public SignerIdentity signer() {
        return SignerIdentity.rsa(KEY_FILE);
    }
}
