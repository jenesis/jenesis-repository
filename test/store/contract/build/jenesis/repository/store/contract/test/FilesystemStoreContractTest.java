package build.jenesis.repository.store.contract.test;

import build.jenesis.repository.store.testkit.StoreFixture;

/** The shared {@code ArtifactStore} contract on the default filesystem backend - no Docker, so this leg always runs. */
class FilesystemStoreContractTest extends StoreContractSuite {

    @Override
    StoreFixture fixture() {
        return new FilesystemStoreFixture();
    }
}
