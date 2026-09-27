package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.FaultInjectingStore.Op;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Retries} holds one writer per node on a key, over a store whose every read and write takes as long as an
 * object store's. Within a node writers of one document take turns, so the contention a compare-and-set meets is one
 * writer per node; each writer here stands for a node, and each increments one counter several times over, so the
 * writers stay in contention for the whole run rather than for one round.
 *
 * <p>A try is paced by the store's own round trip, not only by the backoff between tries: the writers that lose a
 * round re-read after the winner's write has landed, so each round lands a write and the budget of tries is spent
 * one round per peer. The claim is that no writer gives the conflict up and no increment is lost, at the node counts a
 * deployment runs.
 */
class RetriesUnderContentionTest {

    private static final String KEY = "counter";

    /** What one read or one write costs on an object store in the same region, the latency a try is paced by. */
    private static final Duration LATENCY = Duration.ofMillis(30);

    /** Increments per writer: enough that the writers meet in every round, not only the first. */
    private static final int INCREMENTS = 5;

    @TempDir
    Path root;

    @ParameterizedTest(name = "{0} nodes")
    @ValueSource(ints = {2, 4, 8})
    void no_writer_gives_up_and_no_increment_is_lost(int nodes) throws Exception {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        ArtifactStore store = FaultInjectingStore.wrap(filesystem).tracing((op, _) -> {
            if (op == Op.READ_VERSIONED || op == Op.WRITE_VERSIONED) {
                try {
                    Thread.sleep(LATENCY);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int node = 0; node < nodes; node++) {
            writers.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int increment = 0; increment < INCREMENTS; increment++) {
                        Retries.update(store, KEY, current -> String.valueOf(current
                                .map(versioned -> Integer.parseInt(new String(versioned.content(),
                                        StandardCharsets.UTF_8)))
                                .orElse(0) + 1).getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }

        assertThat(failures).as("no writer gave the compare-and-set up").isEmpty();
        assertThat(filesystem.readVersioned(KEY).map(versioned -> new String(versioned.content(),
                StandardCharsets.UTF_8)))
                .as("every increment landed").contains(String.valueOf(nodes * INCREMENTS));
    }
}
