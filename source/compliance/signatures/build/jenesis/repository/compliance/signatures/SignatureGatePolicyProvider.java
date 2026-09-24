package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;

/**
 * Discovers the signature dimension: what an invalid, untrusted, changed or missing publisher signature does, and
 * the optional quality floor beneath them. A deployment without this module records no signature facts and gates on
 * none.
 *
 * <p>The missing-signature verdict is read from a dial of its own on the proxy path, {@code signature-missing-proxy},
 * ALLOW by default - the asymmetry {@link Symmetry} declares and the contract holds this provider to. An upstream
 * carries artifacts published long before its own signing requirement existed; quarantining every one of them would
 * stop the proxy being a proxy, while a hosted publish is the deployment's own supply chain and answers to the
 * stricter dial. Since the pull-through fetches the sidecars an upstream publishes before the screen decides, the
 * proxy dial is a real choice for a deployment mirroring a registry that signs everything.
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
        // Always present: an artifact carrying no signature where its format expects one is itself the case this
        // dimension gates, so there is no "nothing configured" outcome. What decides whether it bites is the dials.
        return GateDimension.of(this, config, path).map(dimension -> {
            SignaturePolicy policy = SignaturePolicy.from(config);
            return dimension.proxy() ? policy.onProxy() : policy;
        });
    }
}
