package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

/** The per-item durable archetype's leg of the shared {@code WalkConsumer} contract. */
class StreamingIndexContractTest extends WalkConsumerContractSuite {

    @Override
    WalkConsumerFixture fixture() {
        return new StreamingIndexFixture();
    }
}
