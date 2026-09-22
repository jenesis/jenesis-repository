package build.jenesis.repository.blobs.test;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Blobs#establish} under the race it exists for.
 *
 * <p>This is the falsifier for a defect a full lane found and a module run never could: four formats provisioned
 * their signing key as <em>check, generate, write the secret, write the public</em>, and two first publishes
 * interleaving stored one pair's secret beside the other pair's public. The repository then signed with a key it
 * did not serve, and an {@code apk} client - which verifies before it will read an index at all - refused
 * everything.
 *
 * <p>So the property under test is not "the value is stored" but the one that was actually violated: <b>every
 * caller ends up holding the same value</b>, including the ones that generated their own and lost.
 */
class EstablishOnceTest {

    private static final String KEY = "keys/established.der";

    @TempDir
    Path root;

    /** The filesystem backend over this test's own directory, configured through the lookup rather than through a
     *  system property, so two tests in one JVM never share a root by accident. */
    private build.jenesis.repository.store.ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void concurrent_callers_all_end_up_with_the_one_established_value() throws Exception {
        Blobs blobs = new Blobs(store());
        int callers = 16;
        AtomicInteger generated = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(callers);
        List<Callable<String>> establish = new ArrayList<>();
        for (int caller = 0; caller < callers; caller++) {
            int mine = caller;
            establish.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                // Each caller generates a DIFFERENT value, which is what makes a lost race observable: with equal
                // values last-writer-wins would look correct and prove nothing.
                return new String(blobs.establish(KEY, () -> {
                    generated.incrementAndGet();
                    return ("value-from-caller-" + mine).getBytes(StandardCharsets.UTF_8);
                }), StandardCharsets.UTF_8);
            });
        }
        List<String> held;
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Future<String>> futures = pool.invokeAll(establish);
            held = new ArrayList<>();
            for (Future<String> future : futures) {
                held.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(Set.copyOf(held))
                .as("every caller holds the one established value, whether it generated it or lost the race")
                .hasSize(1);

        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        assertThat(blobs.read(KEY, stored)).isTrue();
        assertThat(new String(stored.toByteArray(), StandardCharsets.UTF_8))
                .as("and what is stored is what they hold, not a later writer's")
                .isEqualTo(held.getFirst());
        assertThat(generated.get())
                .as("a race may generate more than once - it may never keep more than one")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void a_second_call_never_replaces_an_established_value() throws IOException {
        Blobs blobs = new Blobs(store());
        byte[] first = blobs.establish(KEY, () -> "first".getBytes(StandardCharsets.UTF_8));
        byte[] second = blobs.establish(KEY, () -> "second".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(first, StandardCharsets.UTF_8)).isEqualTo("first");
        assertThat(new String(second, StandardCharsets.UTF_8))
                .as("establish returns what is stored, never what this call would have written")
                .isEqualTo("first");
    }
}
