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
}
