package build.jenesis.repository.compliance;

import module java.base;
import module tools.jackson.databind;
import java.security.Signature;

/**
 * The Dead Simple Signing Envelope as this build produces and admits it: an in-toto statement as the payload, the
 * pre-authentication encoding a signature is made over, and the sign and verify steps on the JDK's {@link Signature}.
 * The bare-key signer, the keyless signer and the admission verifier each carried these lines, and an envelope one
 * of them writes only round-trips through another while all three agree - which is now by construction rather than
 * by keeping three copies in step. The encoding measures the type and the payload in bytes, as the specification
 * says; a derived encoding that measures the payload as a string agrees for ASCII and drifts on any statement
 * carrying other characters, which is why the encoding is spelled out here rather than taken from a library.
 */
public final class Dsse {

    /** The payload type of an in-toto statement, the one envelope kind this build signs and admits. */
    public static final String IN_TOTO = "application/vnd.in-toto+json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Dsse() {
    }

    /**
     * Sign {@code payload} into an in-toto envelope with {@code key}. The {@code keyId} is the envelope's hint at the
     * verification key: {@link ProvenanceSigner#fingerprint} for a long-lived key, and empty for an ephemeral key
     * whose certificate carries the identity - a DSSE verifier (securesystemslib, as Rekor and cosign run it) skips a
     * signature whose non-empty keyid does not match its own derivation, so a named keyid on a keyless envelope would
     * fail the real consumers.
     */
    public static String sign(byte[] payload, PrivateKey key, String keyId) throws GeneralSecurityException {
        String algorithm = algorithm(key);
        if (algorithm == null) {
            throw new GeneralSecurityException("No envelope signature is defined for a " + key.getAlgorithm() + " key.");
        }
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update(preAuthenticationEncoding(IN_TOTO, payload));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("payloadType", IN_TOTO);
        envelope.put("payload", Base64.getEncoder().encodeToString(payload));
        envelope.put("signatures", List.of(Map.of(
                "keyid", keyId, "sig", Base64.getEncoder().encodeToString(signature.sign()))));
        return JSON.writeValueAsString(envelope);
    }

    /** Whether {@code envelope} is an in-toto envelope at least one of whose signatures verifies against {@code key}
     *  over the pre-authentication encoding of the carried payload - the verification a consumer runs. */
    public static boolean verify(String envelope, PublicKey key) {
        Optional<Envelope> parsed = Envelope.parse(envelope);
        return parsed.isPresent() && parsed.get().inToto() && parsed.get().verifiedBy(key);
    }

    /** The JCA algorithm an envelope is signed with for {@code key}: SHA-256 with RSA or ECDSA, or {@code null} for
     *  a key kind no envelope here is signed with. */
    public static String algorithm(Key key) {
        return switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            default -> null;
        };
    }

    /** {@code DSSEv1 SP len(type) SP type SP len(payload) SP payload}, the lengths in bytes. */
    public static byte[] preAuthenticationEncoding(String payloadType, byte[] payload) {
        byte[] type = payloadType.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("DSSEv1 ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(Integer.toString(type.length).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(" ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(type);
        out.writeBytes(" ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(Integer.toString(payload.length).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(" ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** A parsed envelope: its payload type, the decoded payload and every signature that decoded. */
    public record Envelope(String payloadType, byte[] payload, List<byte[]> signatures) {

        /** The envelope object {@code text} is, or empty when it is not one. */
        public static Optional<Envelope> parse(String text) {
            JsonNode root;
            try {
                root = JSON.readTree(text);
            } catch (RuntimeException _) {
                return Optional.empty();
            }
            return from(root);
        }

        /** The envelope object {@code node} is - a payload type, a base64 payload and a signatures array - or empty
         *  when it is not one; a signature that does not decode is skipped, since another in the envelope may still
         *  verify. */
        public static Optional<Envelope> from(JsonNode node) {
            String payloadType = node.path("payloadType").asString(null);
            String payloadB64 = node.path("payload").asString(null);
            JsonNode signatureNodes = node.path("signatures");
            if (payloadType == null || payloadB64 == null || !signatureNodes.isArray()) {
                return Optional.empty();
            }
            byte[] payload;
            try {
                payload = Base64.getDecoder().decode(payloadB64);
            } catch (IllegalArgumentException _) {
                return Optional.empty();
            }
            List<byte[]> signatures = new ArrayList<>();
            for (JsonNode signature : signatureNodes) {
                String sig = signature.path("sig").asString("");
                if (!sig.isBlank()) {
                    try {
                        signatures.add(Base64.getDecoder().decode(sig));
                    } catch (IllegalArgumentException _) {
                        // skip an unparsable signature; another in the envelope may still verify
                    }
                }
            }
            return Optional.of(new Envelope(payloadType, payload, List.copyOf(signatures)));
        }

        /** Whether the payload is an in-toto statement. */
        public boolean inToto() {
            return IN_TOTO.equals(payloadType);
        }

        /** Whether at least one signature verifies against {@code key} over the pre-authentication encoding. */
        public boolean verifiedBy(PublicKey key) {
            String algorithm = algorithm(key);
            if (algorithm == null) {
                return false;
            }
            byte[] encoding = preAuthenticationEncoding(payloadType, payload);
            for (byte[] signature : signatures) {
                try {
                    Signature verifier = Signature.getInstance(algorithm);
                    verifier.initVerify(key);
                    verifier.update(encoding);
                    if (verifier.verify(signature)) {
                        return true;
                    }
                } catch (GeneralSecurityException _) {
                    // a signature that does not match this key: try the next one
                }
            }
            return false;
        }

        /** Whether at least one signature verifies against at least one of {@code keys} - the check that an
         *  attestation was signed by a builder the tenant trusts. */
        public boolean verifiedBy(Collection<PublicKey> keys) {
            for (PublicKey key : keys) {
                if (verifiedBy(key)) {
                    return true;
                }
            }
            return false;
        }
    }
}
