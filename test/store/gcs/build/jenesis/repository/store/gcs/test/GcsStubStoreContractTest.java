package build.jenesis.repository.store.gcs.test;

import build.jenesis.repository.store.testkit.StoreContractSuite;
import build.jenesis.repository.store.testkit.StoreFixture;

/** The shared {@code ArtifactStore} contract on the Cloud Storage backend over its stateful JSON API stub - no
 *  container, so this leg runs in every lane. */
class GcsStubStoreContractTest extends StoreContractSuite {

    @Override
    protected StoreFixture fixture() {
        return new GcsStubStoreFixture();
    }
}
