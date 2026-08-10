package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** the screen whose verdict lives on the read side's leg of the shared publication-hook contract. */
class WithholdingScreenContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new WithholdingScreenFixture();
    }
}
