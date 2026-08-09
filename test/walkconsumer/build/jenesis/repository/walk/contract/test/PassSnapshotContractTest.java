package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

/** The pass snapshot archetype's leg of the shared {@code WalkConsumer} contract - the delivery class a crash-resume
 *  does not converge, held to the weaker claim its commit protocol actually supports. */
class PassSnapshotContractTest extends WalkConsumerContractSuite {

    @Override
    WalkConsumerFixture fixture() {
        return new PassSnapshotFixture();
    }
}
