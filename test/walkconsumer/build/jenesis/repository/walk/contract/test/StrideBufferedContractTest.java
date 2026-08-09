package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

/** The stride durable archetype's leg of the shared {@code WalkConsumer} contract. */
class StrideBufferedContractTest extends WalkConsumerContractSuite {

    @Override
    WalkConsumerFixture fixture() {
        return new StrideBufferedFixture();
    }
}
