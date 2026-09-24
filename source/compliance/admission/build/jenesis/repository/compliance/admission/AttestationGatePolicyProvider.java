package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.Verdict;

/**
 * Discovers the inbound-provenance admission dimension of the gate: it reads the trust anchor from
 * {@code provenance-admission-key} (one or more PEM public keys), the expected builders and sources from
 * {@code provenance-admission-builder} / {@code provenance-admission-source}, and the verdict from
 * {@code provenance-admission-action} (default {@link Verdict#QUARANTINE}), and builds an {@link AttestationPolicy}
 * applied identically on both legs - a mis-attested upstream pull-through is the same risk as a first-party upload, so
 * it is not softened for the proxy. The dimension is named {@code provenance-admission}, so
 * {@code jenreg.provenance-admission=false} turns it off; it also self-disables when no trust anchor is
 * configured ({@link #requiredConfig()}), because without a key there is nothing to verify a signature against and an
 * attestation's builder / source claims would be self-asserted. A key or verdict that does not parse throws, so a
 * live settings rebuild can reject and roll back.
 */
public final class AttestationGatePolicyProvider implements GatePolicyProvider {

    private static final String KEY = "provenance-admission-key";

    @Override
    public String name() {
        return "provenance-admission";
    }

    @Override
    public Set<String> requiredConfig() {
        // Without a trust anchor there is nothing to verify a signature against, so the dimension self-disables (one
        // log line) rather than admitting on unsigned, self-asserted builder / source claims.
        return Set.of(KEY);
    }

    @Override
    public Optional<GatePolicy> create(UnaryOperator<String> config, Path path) {
        return GateDimension.of(this, config, path).flatMap(dimension -> {
            List<PublicKey> keys = dimension.text(KEY)
                    .map(AttestationGatePolicyProvider::keys)
                    .orElseGet(List::of);
            // The trust anchor is the whole "is there anything to gate on" question here: without a key there is
            // nothing to verify a signature against, so the dimension is absent (the requiredConfig() self-disable,
            // restated for a create() called outside resolve()). The action verdict is deliberately not part of it -
            // ALLOW means this dimension evaluates and permits, exactly as it does for every peer.
            return dimension.enforcing(keys, () -> new AttestationPolicy(keys,
                    dimension.entries("provenance-admission-builder"),
                    dimension.entries("provenance-admission-source"),
                    dimension.verdict("provenance-admission-action", Verdict.QUARANTINE)));
        });
    }

    /** Parse one or more PEM {@code SubjectPublicKeyInfo} blocks (the trust anchors) - an RSA or EC public key each; a
     *  non-blank value that carries no readable key throws, so a bad settings save rolls back. */
    static List<PublicKey> keys(String pem) {
        List<PublicKey> keys = new ArrayList<>();
        java.util.regex.Matcher block = Pattern.compile(
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
