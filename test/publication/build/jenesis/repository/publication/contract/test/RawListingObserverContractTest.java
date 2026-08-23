package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

class RawListingObserverContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new RawListingObserverFixture();
    }
}
