package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for a bare RSA signature with no envelope, whose signer is the key file its member is named after
 * (an apk's {@code .SIGN.RSA256.<keyfile>}), as a discovered {@link SignatureScheme} over {@link RsaVerification}.
 * The facts are read from the evidence's location - the member name after the archive separator - since a bare
 * signature states nothing about itself. The trust source that speaks for it is the one whose public-key bundle
 * names that key file, else the first holding unnamed keys; the identity is the key file, the spelling the
 * ecosystem itself uses and a pin can name.
 */
public final class RsaDetachedScheme implements SignatureScheme {

    @Override
    public ArtifactSignatures.Scheme scheme() {
        return ArtifactSignatures.Scheme.RSA_DETACHED;
    }

    @Override
    public String material() {
        return SignerIdentity.RSA;
    }

    @Override
    public Optional<Reading> read(ArtifactSignatures.Evidence evidence) {
        String location = evidence.location();
        String member = location.substring(location.lastIndexOf('!') + 1);
        return RsaVerification.facts(member).map(facts -> new Bare(evidence, facts));
    }

    @Override
    public String unrecognised() {
        return "not a signature member naming its key file and digest";
    }

    private record Bare(ArtifactSignatures.Evidence evidence, RsaVerification.Facts facts) implements Reading {

        @Override
        public boolean heldBy(byte[] material) {
            return material != null && material.length > 0 && RsaVerification.holds(facts, material);
        }

        @Override
        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                throws IOException {
            RsaVerification.Verdict verdict = RsaVerification.verify(covered, evidence.signature(), facts, material);
            SignerIdentity signer = SignerIdentity.rsa(facts.keyFile());
            int bits = verdict.key().map(RsaVerification::bits).orElse(0);
            SignatureQuality quality = SignatureQuality.rsa(bits, facts.hashAlgorithm(), now);
            Result mapped = switch (verdict.result()) {
                case VALID -> Result.VALID;
                case INVALID -> Result.INVALID;
                case NO_KEY -> Result.NO_KEY;
            };
            return new Verification(mapped, signer, "RSA", bits, facts.hashAlgorithm(), null, null, quality, null);
        }
    }
}
