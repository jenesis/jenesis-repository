package build.jenesis.repository.store.s3.test;

import build.jenesis.repository.store.testkit.StoreContractSuite;
import build.jenesis.repository.store.testkit.StoreFixture;

/** The shared {@code ArtifactStore} contract on the S3 backend over its stateful REST stub - no container, so this leg
 *  runs in every lane. */
class S3StubStoreContractTest extends StoreContractSuite {

    @Override
    protected StoreFixture fixture() {
        return new S3StubStoreFixture();
    }
}
