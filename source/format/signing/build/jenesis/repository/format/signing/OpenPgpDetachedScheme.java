package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for a detached OpenPGP signature over the artifact's bytes - a Maven {@code .asc}, a {@code .deb}'s
 * {@code _gpgorigin}, a Terraform {@code SHA256SUMS.sig} - as a discovered {@link SignatureScheme} over
 * {@link OpenPgpVerification}.
 *
 * <p>The trust source that speaks for a signature is the one whose keyring holds its issuer, probed through the
 * verifier's own resolution ({@link OpenPgpVerification#fingerprint} calls what {@link OpenPgpVerification#verify}
 * calls), so a source is chosen exactly when verifying against it would not answer NO_KEY. The probe reads no artifact,
 * so the body is streamed once.
 *
 * <p>A signature naming its issuer only by key id is resolved to the fingerprint from the keyring, so one signer is one
 * identity on the signer index whichever spelling arrived.
 */
public final class OpenPgpDetachedScheme implements SignatureScheme {

    @Override
    public ArtifactSignatures.Scheme scheme() {
        return ArtifactSignatures.Scheme.OPENPGP_DETACHED;
    }

    @Override
    public String material() {
        return SignerIdentity.OPENPGP;
    }

    @Override
    public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
        return OpenPgpVerification.facts(evidence.signature()).map(facts -> new Detached(evidence, facts));
    }

    @Override
    public String unrecognised() {
        return "not an OpenPGP signature";
    }

    /** Whatever a keyserver, a Web Key Directory or GitHub served, binary or armour, as one armoured block for the
     *  discovered bundle; empty for bytes holding no OpenPGP public key. */
    @Override
    public Optional<byte[]> trustMaterial(byte[] served) {
        try {
            return Optional.of(OpenPgpVerification.armoured(served));
        } catch (IOException notKeys) {
            return Optional.empty();
        }
    }

    /** Whether the keyring holds a key by this id, the probe key discovery asks before fetching and after. */
    @Override
    public boolean holdsKey(String keyId, byte[] material) {
        if (material == null || material.length == 0) {
            return false;
        }
        try {
            return OpenPgpVerification.fingerprint(keyId, material).isPresent();
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** What a detached signature's facts say once a keyring is in hand, shared with the clearsigned scheme. */
    static Verification described(OpenPgpVerification.Facts facts, OpenPgpVerification.Result result,
                                  byte[] keyring, Instant now) throws IOException {
        String issuer = facts.fingerprint();
        if (issuer == null && keyring != null) {
            issuer = OpenPgpVerification.fingerprint(facts.keyId(), keyring).orElse(null);
        }
        SignerIdentity signer = SignerIdentity.openpgp(issuer == null ? facts.keyId() : issuer);
        OpenPgpVerification.KeyFacts key = keyring == null
                ? null
                : OpenPgpVerification.keyFacts(facts.keyId(), keyring).orElse(null);
        int bits = key == null ? 0 : key.bits();
        Instant expiry = key == null ? null : key.expiry();
        SignatureQuality quality = SignatureQuality.openPgp(facts.keyAlgorithm(), bits, facts.hashAlgorithm(),
                facts.created(), expiry, now);
        return new Verification(result(result), signer, facts.keyAlgorithm(), bits, facts.hashAlgorithm(),
                facts.created(), expiry, quality, null);
    }

    static Result result(OpenPgpVerification.Result result) {
        return switch (result) {
            case VALID -> Result.VALID;
            case INVALID -> Result.INVALID;
            case NO_KEY -> Result.NO_KEY;
        };
    }

    /** Whether the keyring holds the issuer these facts name, through the verifier's own resolution. */
    static boolean holds(OpenPgpVerification.Facts facts, byte[] keyring) throws IOException {
        return keyring != null && keyring.length > 0
                && OpenPgpVerification.fingerprint(facts.keyId(), keyring).isPresent();
    }

    private record Detached(ArtifactSignatures.Evidence evidence, OpenPgpVerification.Facts facts)
            implements Reading {

        @Override
        public boolean heldBy(byte[] material) throws IOException {
            return holds(facts, material);
        }

        @Override
        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                throws IOException {
            if (material == null || material.length == 0) {
                // With no key material the artifact is read anyway, through the same bound: a screen decides whether it
                // was seen whole from the inspection alone, and an oversized artifact is still reported unverified.
                drain(covered);
            }
            OpenPgpVerification.Result result = OpenPgpVerification.verify(covered, evidence.signature(), material);
            return described(facts, result, material, now);
        }
    }

    /** Read a stream to its end and discard it; the inspector's bound rides out as an {@link IOException}, as for a
     *  verifying read. */
    private static void drain(ArtifactSignatures.Signed covered) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream in = covered.open()) {
            while (in.read(buffer) != -1) {
                // Discarded.
            }
        }
    }
}
