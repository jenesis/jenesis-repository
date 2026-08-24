package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** Drives {@link FeedSplittingObserverFixture} through the shared contract, including the falsification leg - which
 *  is the point of this fixture existing. */
class FeedSplittingObserverContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new FeedSplittingObserverFixture();
    }
}
