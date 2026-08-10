package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** the durable-after-enqueue after-commit archetype's leg of the shared publication-hook contract. */
class OutboxObserverContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new OutboxObserverFixture();
    }
}
