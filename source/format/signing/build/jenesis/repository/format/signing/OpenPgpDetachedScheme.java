package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The verifier for a detached OpenPGP signature over the artifact's bytes - a Maven {@code .asc} sidecar, a
 * {@code .deb}'s {@code _gpgorigin} member, a Terraform {@code SHA256SUMS.sig} - as a discovered
 * {@link SignatureScheme} over {@link OpenPgpVerification}.
 *
 * <p>The trust source that speaks for a signature is the one whose keyring holds its issuer, and the probe is the
 * verifier's own issuer resolution: {@link OpenPgpVerification#verify} resolves the key as {@code key(keyID,
 * keyring)} and {@link OpenPgpVerification#fingerprint} reaches the same call with the same value, so a source is
 * chosen if and only if verifying against it would not answer NO_KEY, and no signature a pooled keyring would have
 * verified is missed. It costs no artifact read - resolving a key id is a lookup, verifying is a digest over the
 * whole body - so the body is still streamed exactly once.
 *
 * <p>The signature names its issuer by key id and, on anything modern, by fingerprint. Where it named only the id
 * and the keyring carries the key, the fingerprint is resolved from there, so one signer is one identity whichever
 * spelling arrived rather than two rows on the signer index for the same key.
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

    /** Whatever a keyserver, a Web Key Directory or GitHub served - binary transferable keys or armour - as the one
     *  armoured block the discovered bundle appends; empty for bytes that hold no OpenPGP public key. */
    @Override
    public Optional<byte[]> trustMaterial(byte[] served) {
        try {
            return Optional.of(OpenPgpVerification.armoured(served));
        } catch (IOException notKeys) {
            return Optional.empty();
        }
    }

    /** Whether the keyring holds a key by this id, by fingerprint lookup - the probe the key-discovery pass asks
     *  before fetching and after. */
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
                // The verifier resolves the issuer before it streams, so with no key material it would answer
                // without ever opening the artifact. The artifact is read anyway: the inspector declares that it
                // reads whole bodies, and a screen decides whether an artifact was seen whole from the inspection
                // alone - it has no way to know that keys happened to be unconfigured. Reading through the same
                // bound means an oversized artifact is still reported unverified rather than pinning the thread.
                drain(covered);
            }
            OpenPgpVerification.Result result = OpenPgpVerification.verify(covered, evidence.signature(), material);
            return described(facts, result, material, now);
        }
    }

    /** Read a stream to its end and discard it: what matters is that the artifact was pulled, not what it held. An
     *  {@link IOException} the stream raises - the inspector's bound - rides out exactly as a verifying read's
     *  would. */
    private static void drain(ArtifactSignatures.Signed covered) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream in = covered.open()) {
            while (in.read(buffer) != -1) {
                // discarded by design
            }
        }
    }
}
