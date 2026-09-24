package build.jenesis.repository.health.store;

import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Discovers the store-backed health ledger: stateless, binding the per-request scoped store on each call, so one
 * instance serves every tenant and repository.
 */
public final class StoreHealthLedgerProvider implements HealthLedgerProvider {

    @Override
    public HealthLedger over(ArtifactStore store) {
        return new StoreHealthLedger(store);
    }
}
