package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for an OpenPGP clearsigned document - Helm's {@code .prov} - as a discovered {@link SignatureScheme}:
 * the signature is verified over the canonical form of the text the document carries, and the covered bytes are
 * what that text must name. Helm's provenance names its chart by SHA-256, and a statement that names other bytes is
 * INVALID for the artifact exactly as a detached signature over other bytes is. The keyring is chosen by issuer as
 * for a detached signature, through {@link OpenPgpDetachedScheme}'s probe.
 *
 * <p>Covering evidence - a document that names its artifact through {@link ArtifactSignatures.Evidence#named} - is
 * compared by the inspector once for every covering scheme, so this verifier reads the covered bytes only for a
 * direct statement in Helm's grammar.
 */
public final class OpenPgpClearsignedScheme implements SignatureScheme {

    @Override
    public ArtifactSignatures.Scheme scheme() {
        return ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED;
    }

    @Override
    public String material() {
        return SignerIdentity.OPENPGP;
    }

    @Override
    public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
        Optional<OpenPgpVerification.Facts> facts = OpenPgpVerification.facts(evidence.signature());
        Optional<byte[]> cleartext = OpenPgpVerification.cleartext(evidence.signature());
        if (facts.isEmpty() || cleartext.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Clearsigned(evidence, facts.get(), cleartext.get()));
    }

    @Override
    public String unrecognised() {
        return "not an OpenPGP clearsigned document";
    }

    private record Clearsigned(ArtifactSignatures.Evidence evidence, OpenPgpVerification.Facts facts,
                               byte[] cleartext) implements Reading {

        @Override
        public boolean heldBy(byte[] material) throws IOException {
            return OpenPgpDetachedScheme.holds(facts, material);
        }

        @Override
        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                throws IOException {
            OpenPgpVerification.Result result = OpenPgpVerification.verifyClearsigned(evidence.signature(), material);
            if (result != OpenPgpVerification.Result.INVALID && evidence.named() == null
                    && !OpenPgpVerification.provenanceNames(cleartext, covered)) {
                result = OpenPgpVerification.Result.INVALID;
            }
            return OpenPgpDetachedScheme.described(facts, result, material, now);
        }
    }
}
