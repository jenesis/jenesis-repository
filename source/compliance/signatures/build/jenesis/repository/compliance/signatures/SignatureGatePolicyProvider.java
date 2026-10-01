package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;

/**
 * Discovers the signature dimension: what an invalid, untrusted, changed or missing publisher signature does, and the
 * optional quality floor. On the proxy path a missing signature reads its own dial, {@code signature-missing-proxy},
 * ALLOW by default ({@link Symmetry}), since an upstream holds artifacts older than its signing requirement; the
 * pull-through fetches sidecars first, so stricter is a real choice for a registry that signs everything.
 */
public final class SignatureGatePolicyProvider implements GatePolicyProvider {

    @Override
    public String name() {
        return "signatures";
    }

    @Override
    public Symmetry symmetry() {
        return Symmetry.SOFTENED_ON_PROXY;
    }

    @Override
    public Optional<GatePolicy> create(UnaryOperator<String> config, Path path) {
        // Always present, since a missing signature is itself a case it gates; the dials decide.
        return GateDimension.of(this, config, path).map(dimension -> {
            SignaturePolicy policy = SignaturePolicy.from(config);
            return dimension.proxy() ? policy.onProxy() : policy;
        });
    }
}
