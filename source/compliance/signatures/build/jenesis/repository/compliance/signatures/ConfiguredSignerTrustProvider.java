package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.SignerTrustProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The trust a deployment gets from its own configuration: the keys an operator supplied and the signers they pinned.
 *
 * <p>It reads the effective per-tenant lookup it is handed - the same one every gate dimension is built from - rather
 * than the process environment or a store of its own. That distinction cost real time: a dial written through
 * {@code PUT /api/settings/{key}} reaches the gate through the live snapshot and never changes the boot environment,
 * so an earlier version reading {@code Features.settings()} answered "no keys configured" for a deployment whose
 * operator had configured them, and every signature reported untrusted while the settings screen showed the keyring
 * plainly.
 *
 * <p>It holds no durable state, so the store is ignored here: continuity - who signed a coordinate's earlier versions
 * - is a store-backed provider's to answer.
 */
public final class ConfiguredSignerTrustProvider implements SignerTrustProvider {

    @Override
    public SignerTrust over(UnaryOperator<String> config, ArtifactStore store) {
        SignerTrust configured = ConfiguredSignerTrust.from(config == null ? key -> null : config);
        if (store == null) {
            return configured;
        }
        // The configured trust first, so an operator's pin answers an expectation ahead of what was learned and a
        // key the operator supplied - a pasted Sigstore root included - identifies a signer ahead of the fetched
        // one;
        // the continuity part holds no keys and admits nobody; the discovered keys, only where the operator
        // named a source, verify without admitting unless the operator accepted the source outright; the
        // provenance part, only where the operator named an issuer, holds nothing and admits a keyless identity
        // that is a workflow of the repository the coordinate's own metadata declares; and the fetched trusted
        // root holds the Sigstore material a deployment that pasted none verifies bundles against, admitting
        // nobody, since a root says a bundle is genuine and never who may sign this coordinate.
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
