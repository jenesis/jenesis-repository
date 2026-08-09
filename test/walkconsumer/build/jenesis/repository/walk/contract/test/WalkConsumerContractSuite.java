package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import build.jenesis.repository.walk.testkit.WalkConsumerContract;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture;
import build.jenesis.repository.walk.testkit.WalkHarness;
import module org.junit.jupiter.api;

import module java.base;

/**
 * The JUnit driver for one consumer's leg of the shared {@code WalkConsumer} contract. Everything consumer-specific
 * lives in the {@link WalkConsumerFixture} a subclass supplies; the checks come from the testkit, so a new consumer is
 * covered by a fixture and a four-line subclass rather than by another hand-written crash-recovery suite.
 *
 * <p>Each contract property becomes one dynamic test named for its consumer and its expectation, and every check gets
 * its own freshly created, empty store wrapped in a {@link FaultInjectingStore} - absence and convergence are both
 * what these checks assert, so a store carrying another check's rows would weaken them.
 *
 * <p><b>Why the walk is constructed rather than resolved here.</b> Two knobs a crash check needs are not settings a
 * deployment has: a checkpoint stride small enough that a two-dozen-artifact corpus really spans several strides, and
 * a clock the suite can move so a dead worker's claim expires without the suite sleeping through a real lease (the
 * walk refuses to steal a live holder's segment, so a resume is not even attempted before the lease runs out). The
 * SPI resolution path is not skipped, only moved: {@code WalkConsumerCensusTest} asserts that
 * {@link WalkProvider#resolve} answers the same reference implementation on this graph.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class WalkConsumerContractSuite {

    /** Items per durable cursor commit. Small on purpose: the corpus spans three strides plus one artifact, so a
     *  crash inside a stride, at a stride boundary and at a non-boundary segment end are all distinct positions. */
    private static final int CHECKPOINT = 4;

    /** How long a segment claim stands. The suite never waits it out - it moves the clock past it instead. */
    private static final Duration LEASE = Duration.ofMinutes(10);

    @TempDir
    Path root;

    /** The consumer under test. */
    abstract WalkConsumerFixture fixture();

    @TestFactory
    Stream<DynamicTest> the_walk_consumer_contract() {
        WalkConsumerFixture fixture = fixture();
        return WalkConsumerContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.consumer() + ": " + check.name(),
                () -> {
                    Harness harness = new Harness();
                    check.body().run(fixture, harness,
                            store(fixture.consumer() + "-" + check.property()));
                }));
    }

    private FaultInjectingStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return FaultInjectingStore.wrap(ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? directory.toString() : null));
    }

    /** One walk over one movable clock: {@code expireClaims} is what stands in for "the crashed node stayed dead".
     *  One segment per pass, so a crash check reads exactly one unambiguous cursor. */
    private static final class Harness implements WalkHarness {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-09T00:00:00Z"));
        private final ArtifactWalk walk = new StoreArtifactWalk(CHECKPOINT, 1, LEASE, new MovableClock(now));

        @Override
        public ArtifactWalk walk() {
            return walk;
        }

        @Override
        public int checkpoint() {
            return CHECKPOINT;
        }

        @Override
        public void expireClaims() {
            now.updateAndGet(instant -> instant.plus(LEASE).plusSeconds(60));
        }
    }

    /** A {@link Clock} reading a mutable instant - the walk takes its clock as a constructor argument precisely so a
     *  lease can be aged in a unit test. */
    private static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now;

        private MovableClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
