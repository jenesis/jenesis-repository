package build.jenesis.repository.store.contract.test;

import build.jenesis.repository.store.testkit.StoreFixture;
import build.jenesis.repository.store.testkit.StoreContractSuite;

/** The shared {@code ArtifactStore} contract on the default filesystem backend - no Docker, so this leg always runs. */
class FilesystemStoreContractTest extends StoreContractSuite {

    @Override
    protected StoreFixture fixture() {
        return new FilesystemStoreFixture();
    }
}
