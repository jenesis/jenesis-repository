package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The composed signer trust: the configured keys and pins, continuity, discovered keys, provenance and the fetched
 * Sigstore root. It reads the effective per-tenant lookup it is handed, as every gate dimension does, since a setting
 * written at runtime never reaches the boot environment.
 */
public final class ConfiguredSignerTrustProvider implements SignerTrustProvider {

    @Override
    public SignerTrust over(UnaryOperator<String> config, ArtifactStore store) {
        SignerTrust configured = ConfiguredSignerTrust.from(config == null ? key -> null : config);
        if (store == null) {
            return configured;
        }
        // The configured part first, so a pin and supplied material outrank what was learned or fetched; the others
        // join where an operator switched them on.
        List<SignerTrust> parts = new ArrayList<>(List.of(configured, new ContinuityTrust(store),
                new FetchedTrustedRoot(store)));
        if (KeyDiscoveryTask.enabled(config)) {
            parts.add(new DiscoveredKeys(store, KeyDiscoveryTask.accepts(config)));
        }
        Set<String> issuers = ProvenanceTrust.issuers(config);
        if (!issuers.isEmpty()) {
            parts.add(new ProvenanceTrust(store, issuers));
        }
        return SignerTrust.composite(parts);
    }
}
