package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** the best-effort after-commit archetype's leg of the shared publication-hook contract. */
class IndexObserverContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new IndexObserverFixture();
    }
}
