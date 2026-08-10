package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** the pre-commit hold-release hook's leg of the shared publication-hook contract. */
class OverrideHookContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new OverrideHookFixture();
    }
}
