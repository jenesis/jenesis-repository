package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for an OpenPGP clearsigned document, Helm's {@code .prov}, as a discovered {@link SignatureScheme}: the
 * signature is verified over the canonical form of the carried text, and the covered bytes are what that text must
 * name; a statement naming other bytes is INVALID. The keyring is chosen by issuer through
 * {@link OpenPgpDetachedScheme}'s probe. Covering evidence ({@link ArtifactSignatures.Evidence#named}) is compared by
 * the inspector for every covering scheme, so this reads the covered bytes only for a direct statement in Helm's
 * grammar.
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

    /** The keyring is chosen by issuer through {@link OpenPgpDetachedScheme}'s probe, which answers for the key id. */
    @Override
    public boolean holdsKey(String keyId, byte[] material) {
        return false;
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
