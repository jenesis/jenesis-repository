/**
 * Focused unit tests for the shared artifact-walk SPI and its {@code store} reference implementation, all driven
 * against a real {@code FilesystemArtifactStore} rooted at a JUnit
 * {@code @TempDir} with an injectable test clock, so every distributed-systems claim is exercised without a network:
 * total lexicographic order and exactly-once coverage per pass; the checkpoint cursor making a crash-resume re-visit
 * at most one stride at every kill point, never restarting; the static segment plan (child packing, the second-level
 * descent, the hex-nibble cuts over a flat content-addressed namespace); the claim-in-segment compare-and-set model
 * (two racing claimants resolve to one winner, a live holder is refused - never stolen - and an expired holder's
 * segment is reclaimed from its last committed cursor by another "node"); the concurrent-publication visibility
 * contract (seen this pass or guaranteed the next); pass lifecycle and generation turnover; the
 * {@code WalkProvider} ServiceLoader resolution with its {@code jenreg.walk} selection; and the
 * {@code ArtifactStore.page} ordered-paging contract - the filesystem backend's bounded native override against the
 * interface default they must both honour.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.walk
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.walk.test {
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.walk.test.DiscoverableWalkConsumer,
                 build.jenesis.repository.walk.test.FeatureGatedWalkConsumer;
}
