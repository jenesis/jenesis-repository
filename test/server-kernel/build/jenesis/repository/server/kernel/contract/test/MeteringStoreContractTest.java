package build.jenesis.repository.server.kernel.contract.test;

import build.jenesis.repository.store.testkit.StoreContractSuite;
import build.jenesis.repository.store.testkit.StoreFixture;

/** The shared {@code ArtifactStore} contract on the metering decorator over the filesystem store. */
class MeteringStoreContractTest extends StoreContractSuite {
    @Override
    protected StoreFixture fixture() {
        return new MeteringStoreFixture();
    }
}
