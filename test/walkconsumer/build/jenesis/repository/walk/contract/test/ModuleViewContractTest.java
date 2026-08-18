package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

/** The shipped Maven module-view repair's leg of the shared {@code WalkConsumer} contract. */
class ModuleViewContractTest extends WalkConsumerContractSuite {

    @Override
    WalkConsumerFixture fixture() {
        return new ModuleViewFixture();
    }
}
