package build.jenesis.repository.store.azure.test;

import build.jenesis.repository.store.testkit.StoreContractSuite;
import build.jenesis.repository.store.testkit.StoreFixture;

/** The shared {@code ArtifactStore} contract on the Azure Blob backend over its stateful service stub - no container, so this leg
 *  runs in every lane. */
class AzureStubStoreContractTest extends StoreContractSuite {

    @Override
    protected StoreFixture fixture() {
        return new AzureStubStoreFixture();
    }
}
