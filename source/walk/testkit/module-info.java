/**
 * The walk-consumer contract kit: the executable {@code WalkConsumer} contract and the fixture seam one consumer
 * registers with.
 *
 * <p>{@code WalkConsumerContract} states the two promises the walk SPI has always made in prose - idempotent delivery
 * and at-least-once crash-resume - as checks: a corpus spanning several checkpoint strides is enumerated, the pass is
 * killed at six named points (before the first delivery, mid-stride, a full stride with the cursor commit dying before
 * it lands, the same with it landing but the caller never learning, the terminal segment commit, and the
 * pass-completion hook), the consumer is rebuilt as a <em>fresh instance</em> - a crashed process keeps no memory -
 * and the pass is resumed. {@code WalkConsumerFixture} is how one consumer registers, and the reason the kit can span
 * consumers that hold entirely different bytes: the fixture declares {@code projection}, its own normalised view of
 * its durable state, and {@code Delivery}, the durability it actually rides. Convergence is then an equality between
 * what the fixture declared and what the store holds - never a comparison of layouts the kit has no business owning -
 * and no consumer is held to a guarantee stronger than its declared class, which for a pass-snapshot rebuilder means
 * "converged <em>or</em> visibly degraded, but never a fragment published as a whole view".
 *
 * <p>The crash itself is the store testkit's {@code FaultInjectingStore}, armed off the consumer's own delivery count
 * rather than off the walk's internal store-call sequence, so a crash point stays where it is named when the walk
 * implementation changes - and every check re-derives from the durable pass state that the crash really landed there.
 * {@code WalkHarness} is what the JUnit driver supplies: the walk, its checkpoint stride, and the ability to age a
 * dead worker's claim past its lease so a resume is possible at all.
 *
 * <p>The module depends only on the walk SPI and the store testkit - no junit, no assertion library, no walk
 * implementation, no server - so both this repository's and the downstream distribution's test modules can require it
 * for their own consumer fixtures exactly as they already require the store testkit. The classes are test doubles;
 * nothing here provides a service, so the module is inert on a runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.walk.testkit {
    requires transitive build.jenesis.repository.walk;
    requires transitive build.jenesis.repository.store.testkit;
    exports build.jenesis.repository.walk.testkit;
}
