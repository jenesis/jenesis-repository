package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.Verdict;

/**
 * Discovers the inbound-provenance admission dimension: trust anchors from {@code provenance-admission-key} (one or
 * more PEM public keys), expected builders and sources from {@code provenance-admission-builder} /
 * {@code provenance-admission-source}, and the verdict from {@code provenance-admission-action} (default
 * {@link Verdict#QUARANTINE}), building an {@link AttestationPolicy} applied identically to uploads and pull-through
 * fetches. {@code jenrepo.provenance-admission=false} turns it off, and it self-disables with no trust anchor
 * ({@link #requiredConfig()}), since builder and source claims would then be self-asserted. A key or verdict that does
 * not parse throws, so a live settings rebuild rolls back.
 */
public final class AttestationGatePolicyProvider implements GatePolicyProvider {

    private static final String KEY = "provenance-admission-key";

    @Override
    public String name() {
        return "provenance-admission";
    }

    @Override
    public Set<String> requiredConfig() {
        // No trust anchor, nothing to verify against: self-disable rather than admit on self-asserted claims.
        return Set.of(KEY);
    }

    @Override
    public Optional<GatePolicy> create(UnaryOperator<String> config, Path path) {
        return GateDimension.of(this, config, path).flatMap(dimension -> {
            List<PublicKey> keys = dimension.text(KEY)
                    .map(AttestationGatePolicyProvider::keys)
                    .orElseGet(List::of);
            // The trust anchor decides whether there is anything to gate on (the requiredConfig() rule, for a create()
            // called outside resolve()). The action does not: ALLOW evaluates and permits, as for every peer dimension.
            return dimension.enforcing(keys, () -> new AttestationPolicy(keys,
                    dimension.entries("provenance-admission-builder"),
                    dimension.entries("provenance-admission-source"),
                    dimension.verdict("provenance-admission-action", Verdict.QUARANTINE)));
        });
    }

    /** Parse one or more PEM {@code SubjectPublicKeyInfo} blocks, an RSA or EC key each; a non-blank value with no
     *  readable key throws, so a bad save rolls back. */
    static List<PublicKey> keys(String pem) {
        List<PublicKey> keys = new ArrayList<>();
        Matcher block = Pattern.compile(
                "-----BEGIN PUBLIC KEY-----(.*?)-----END PUBLIC KEY-----", Pattern.DOTALL).matcher(pem);
        while (block.find()) {
            keys.add(publicKey(Base64.getMimeDecoder().decode(block.group(1).replaceAll("\\s", ""))));
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "setting '" + KEY + "' must be one or more PEM -----BEGIN PUBLIC KEY----- blocks");
        }
        return List.copyOf(keys);
    }

    private static PublicKey publicKey(byte[] der) {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(spec);
            } catch (GeneralSecurityException _) {
                // not this algorithm; try the next
            }
        }
        throw new IllegalArgumentException("setting '" + KEY + "' is not a readable RSA or EC public key");
    }
}
