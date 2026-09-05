package build.jenesis.repository.store.contract.test;

import build.jenesis.repository.store.testkit.StoreContractSuite;
import build.jenesis.repository.store.testkit.StoreFixture;

/** The shared {@code ArtifactStore} contract on the quota decorator over the filesystem store. */
class QuotaStoreContractTest extends StoreContractSuite {
    @Override
    protected StoreFixture fixture() {
        return new QuotaStoreFixture();
    }
}
