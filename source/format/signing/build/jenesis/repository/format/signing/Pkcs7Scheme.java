package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for a PKCS#7 (CMS) signed-data structure carrying its signer's chain - NuGet's {@code .signature.p7s},
 * Swift's detached signature - as a discovered {@link SignatureScheme} over {@link Pkcs7Verification}. The signer is
 * identified from the carried certificate, the trust source is the one whose bundle anchors its chain, and the covered
 * bytes are streamed once. The verifier tells encapsulated and detached structures apart.
 */
public final class Pkcs7Scheme implements SignatureScheme {

    @Override
    public ArtifactSignatures.Scheme scheme() {
        return ArtifactSignatures.Scheme.PKCS7;
    }

    @Override
    public String material() {
        return SignerIdentity.X509;
    }

    @Override
    public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
        return Pkcs7Verification.facts(evidence.signature()).map(facts -> new Structure(evidence, facts));
    }

    @Override
    public String unrecognised() {
        return "not a PKCS#7 signature carrying its signer's certificate";
    }

    /** An operator-supplied key or anchor of this scheme names whom it admits. */
    @Override
    public boolean materialNamesSigner() {
        return true;
    }

    /** This scheme's material is only ever supplied by an operator, never discovered. */
    @Override
    public Optional<byte[]> trustMaterial(byte[] served) {
        return Optional.empty();
    }

    /** A signature of this scheme does not name its key by an id, so no material is looked up by one. */
    @Override
    public boolean holdsKey(String keyId, byte[] material) {
        return false;
    }

    private record Structure(ArtifactSignatures.Evidence evidence, Pkcs7Verification.Facts facts)
            implements Reading {

        @Override
        public boolean heldBy(byte[] material) throws IOException {
            return material != null && material.length > 0
                    && Pkcs7Verification.anchored(evidence.signature(), material);
        }

        @Override
        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                throws IOException {
            Pkcs7Verification.Result result = Pkcs7Verification.verify(covered, evidence.signature(), material);
            SignerIdentity signer = SignerIdentity.x509(facts.spkiSha256());
            SignatureQuality quality = SignatureQuality.x509(facts.keyAlgorithm(), facts.keyBits(),
                    facts.hashAlgorithm(), facts.signingTime(), facts.notAfter(), now);
            Result mapped = switch (result) {
                case VALID -> Result.VALID;
                case INVALID -> Result.INVALID;
                case NO_KEY -> Result.NO_KEY;
            };
            return new Verification(mapped, signer, facts.keyAlgorithm(), facts.keyBits(), facts.hashAlgorithm(),
                    facts.signingTime(), facts.notAfter(), quality, null);
        }
    }
}
