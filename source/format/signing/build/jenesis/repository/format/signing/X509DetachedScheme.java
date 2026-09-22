package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for a bare signature whose signer's X.509 chain the artifact carries itself
 * ({@link ArtifactSignatures.Evidence#signer}) - a gem's member signatures beside its gemspec's {@code cert_chain} -
 * as a discovered {@link SignatureScheme} over {@link X509Verification}. The chain identifies the signer with no
 * trust at all; the trust source that speaks for it is the one whose certificate bundle anchors the chain.
 */
public final class X509DetachedScheme implements SignatureScheme {

    @Override
    public ArtifactSignatures.Scheme scheme() {
        return ArtifactSignatures.Scheme.X509_DETACHED;
    }

    @Override
    public String material() {
        return SignerIdentity.X509;
    }

    @Override
    public Optional<Reading> read(ArtifactSignatures.Evidence evidence) {
        if (evidence.signer() == null) {
            return Optional.empty();
        }
        return X509Verification.facts(evidence.signer()).map(facts -> new Chained(evidence, facts));
    }

    @Override
    public String unrecognised() {
        return "the artifact carries a signature but no certificate chain naming its signer";
    }

    private record Chained(ArtifactSignatures.Evidence evidence, X509Verification.Facts facts) implements Reading {

        @Override
        public boolean heldBy(byte[] material) {
            return material != null && material.length > 0 && X509Verification.anchored(evidence.signer(), material);
        }

        @Override
        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                throws IOException {
            X509Verification.Verdict verdict = X509Verification.verify(covered, evidence.signature(),
                    evidence.signer(), material);
            SignerIdentity signer = SignerIdentity.x509(facts.spkiSha256());
            String digest = verdict.hashAlgorithm().orElse(null);
            SignatureQuality quality = SignatureQuality.x509(facts.keyAlgorithm(), facts.keyBits(), digest, null,
                    facts.notAfter(), now);
            Result mapped = switch (verdict.result()) {
                case VALID -> Result.VALID;
                case INVALID -> Result.INVALID;
                case NO_KEY -> Result.NO_KEY;
            };
            return new Verification(mapped, signer, facts.keyAlgorithm(), facts.keyBits(), digest, null,
                    facts.notAfter(), quality, null);
        }
    }
}
