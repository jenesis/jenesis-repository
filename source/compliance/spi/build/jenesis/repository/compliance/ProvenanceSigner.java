package build.jenesis.repository.compliance;

import module java.base;

/**
 * Signs a provenance statement into an attestation envelope a consumer can verify, and publishes the matching
 * public key - so a consumer can check that an artifact this repository serves is exactly the one it attested. How
 * the envelope is built (DSSE with an RSA key from a PEM, an HSM-backed signer) is the implementation's part,
 * supplied by a {@link ProvenanceSignerProvider} discovered with {@link ServiceLoader}. The {@link #disabled()
 * disabled} signer stands in when no implementation is installed or configured, so the provenance endpoint can
 * answer "not configured" rather than the signer being absent.
 */
public interface ProvenanceSigner {

    /** Whether a signing key is configured; the other methods refuse when it is not. */
    boolean enabled();

    /** The key identifier carried in the envelope, or {@code null} when disabled. */
    String keyId();

    /**
     * The identifier {@link #keyId()} should report for {@code publicKey}: the SHA-256 of its encoded form, hex.
     *
     * <p>It belongs to the contract rather than to an implementation because a consumer matches the id in an
     * envelope against the key it was handed, so two signers deriving it differently would publish two ids for one
     * key and the match would fail for a reason nothing names. Both implementations had computed it privately with
     * the same six lines, so they agreed - by coincidence rather than by construction, and only until one changed.
     */
    static String fingerprint(PublicKey publicKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /** The PEM-encoded public key a consumer verifies the envelope with. */
    String publicKeyPem();

    /** The PEM-encoded certificate chain (leaf first) binding the signing key to an identity, for a signer whose
     *  verification material is certificate-shaped - a keyless Fulcio signer whose short-lived certificate carries
     *  the OIDC identity. {@code null} for a bare-key signer, whose {@link #publicKeyPem()} is the whole story. */
    default String certificateChainPem() {
        return null;
    }

    /** Wrap a JSON-serialisable statement in a signed envelope, returned as the envelope JSON. */
    String sign(Object statement) throws GeneralSecurityException;

    /**
     * Sign a statement and hand back the envelope <em>with the verification material bound to it at signing
     * time</em>: the certificate chain that certified the signing key and the transparency-log entry recording the
     * envelope. The material must travel with the envelope rather than be re-read later - a keyless signer's
     * certificate rotates every few minutes, so the chain published for "the current key" may no longer be the one
     * that signed. A bare-key signer needs neither and this default answers both as absent.
     */
    default Attestation attest(Object statement) throws GeneralSecurityException {
        return new Attestation(sign(statement), certificateChainPem(), null);
    }

    /** A signed attestation and its verification material: the envelope JSON, the PEM certificate chain that
     *  certifies the signing key ({@code null} for a bare-key signer) and the transparency-log entry recording the
     *  envelope ({@code null} when no log is configured). */
    record Attestation(String envelope, String certificateChainPem, TransparencyLogEntry transparencyLog) {
    }

    /**
     * An entry in an append-only transparency log (Rekor) recording a signed envelope, carried next to the envelope
     * so a consumer can check the log's inclusion proof offline and audit the entry in the log itself. The field
     * shapes are the log's own: hex hashes, a base64 {@code canonicalizedBody} (whose {@code envelopeHash} is the
     * SHA-256 of the exact envelope string), and a base64 {@code signedEntryTimestamp} - the log's signed receipt
     * over the entry, verifiable against the log's published public key.
     */
    record TransparencyLogEntry(String uuid, String logId, long logIndex, long integratedTime,
                                String signedEntryTimestamp, String canonicalizedBody, InclusionProof inclusionProof) {
    }

    /** An RFC 6962/9162 Merkle inclusion proof: the audit path from the entry's leaf hash to the log's signed root,
     *  with the checkpoint (signed tree head) the root was published under. */
    record InclusionProof(long logIndex, long treeSize, String rootHash, List<String> hashes, String checkpoint) {
    }

    /** The shared signer with no key: {@link #enabled()} is false and signing is refused. It is a singleton so a
     *  caller can tell "no signer is active" by identity ({@code signer == ProvenanceSigner.disabled()}). */
    ProvenanceSigner DISABLED = new ProvenanceSigner() {

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String keyId() {
            return null;
        }

        @Override
        public String publicKeyPem() {
            throw new IllegalStateException("No signing key is configured.");
        }

        @Override
        public String sign(Object statement) throws GeneralSecurityException {
            throw new GeneralSecurityException("No signing key is configured.");
        }
    };

    static ProvenanceSigner disabled() {
        return DISABLED;
    }
}
