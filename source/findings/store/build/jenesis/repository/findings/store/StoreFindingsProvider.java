package build.jenesis.repository.findings.store;

import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Discovers the store-backed findings ledger: stateless, binding the per-request scoped store on each call, so one
 * instance serves every tenant and repository.
 */
public final class StoreFindingsProvider implements FindingsProvider {

    @Override
    public Findings over(ArtifactStore store) {
        return new StoreFindings(store);
    }
}
