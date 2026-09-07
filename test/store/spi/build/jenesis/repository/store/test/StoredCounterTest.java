package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StoredCounter}: a decimal total moved by deltas under compare-and-set, floored at zero, reading corrupt as
 * zero, best-effort on a lost race with the loss reported to the caller, and set whole by the pass that recomputes it.
 */
class StoredCounterTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default");
    }

    @Test
    void deltas_move_the_total_and_never_below_zero() throws IOException {
        StoredCounter used = new StoredCounter(store, ".system/quota/used");
        assertThat(used.read()).as("never counted reads as zero").isZero();
        assertThat(used.add(1_000)).isTrue();
        assertThat(used.add(500)).isTrue();
        assertThat(used.read()).isEqualTo(1_500);
        assertThat(used.add(-2_000)).isTrue();
        assertThat(used.read()).as("floored at zero, never negative").isZero();
        assertThat(used.key()).isEqualTo(".system/quota/used");
    }

    @Test
    void a_corrupt_total_reads_as_zero_and_a_recompute_replaces_whatever_stood() throws IOException {
        StoredCounter used = new StoredCounter(store, "sizes/folder");
        store.writeVersioned("sizes/folder", "not a number".getBytes(StandardCharsets.UTF_8), null);
        assertThat(used.read()).as("a garbage object never throws through the fold").isZero();
        assertThat(used.add(10)).isTrue();
        assertThat(used.read()).as("the fold floors the corrupt value and moves on").isEqualTo(10);
        used.set(42);
        assertThat(used.read()).isEqualTo(42);
        used.set(-5);
        assertThat(used.read()).as("a recompute is floored too").isZero();
    }

    @Test
    void a_delta_that_loses_every_retry_is_dropped_and_reported_not_thrown() throws IOException {
        new StoredCounter(store, "quota/used").add(100);
        FaultInjectingStore losing = FaultInjectingStore.wrap(store);
        for (int lost = 0; lost < Retries.COMPARE_AND_SET; lost++) {
            losing.conflictNext(FaultInjectingStore.keyContaining("quota/used"));
        }
        assertThat(new StoredCounter(losing, "quota/used").add(50))
                .as("the caller is told, and logs the drop naming the pass that recomputes").isFalse();
        assertThat(new StoredCounter(store, "quota/used").read()).as("the last landed total stands").isEqualTo(100);
        assertThat(losing.calls(FaultInjectingStore.Op.WRITE_VERSIONED)).isEqualTo(Retries.COMPARE_AND_SET);
    }

    @Test
    void deferred_deltas_are_counted_at_once_here_and_written_as_one_compare_and_set_per_flush() throws IOException {
        StoredCounter counter = new StoredCounter(store, "quota/used");
        counter.set(100);
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        StoredCounter deferred = new StoredCounter(counting, "quota/used");
        for (int delta = 1; delta <= 5; delta++) {
            deferred.addLater(delta);
        }
        assertThat(deferred.read()).as("this node counts what it has not written yet").isEqualTo(115);
        assertThat(counting.calls(FaultInjectingStore.Op.WRITE_VERSIONED)).as("nothing written before the flush").isZero();
        assertThat(new StoredCounter(FaultInjectingStore.peer(store), "quota/used").read())
                .as("another node - a store of another identity - sees the stored value only").isEqualTo(100);
        assertThat(StoredCounter.flushNow()).isGreaterThanOrEqualTo(1);
        assertThat(counting.calls(FaultInjectingStore.Op.WRITE_VERSIONED)).as("five deltas, one compare-and-set").isEqualTo(1);
        assertThat(new StoredCounter(store, "quota/used").read()).isEqualTo(115);
        deferred.addLater(-15);
        deferred.set(50);
        StoredCounter.flushNow();
        assertThat(new StoredCounter(store, "quota/used").read()).as("a recompute supersedes a pending delta").isEqualTo(50);
    }

}
