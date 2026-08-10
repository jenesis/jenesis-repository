package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** the verdict-bearing screen that votes from durable state's leg of the shared publication-hook contract. */
class RecordingScreenContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new RecordingScreenFixture();
    }
}
