package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.FaultInjectingStore.Op;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Retries}: the one compare-and-set loop, and its endings. A lost race is retried against a fresh read, so the
 * mutation runs again over what the peer left; exhaustion throws by name ({@code update}, {@code decide}) or answers
 * {@code false} / empty ({@code tryUpdate}, {@code tryDecide}); a {@code null} body or a {@code keep} verdict writes
 * nothing; a {@code delete} verdict removes the key; a verdict's result is what the caller gets from the try that
 * landed. This class had no test of its own while twenty-five loops were being folded onto it - which is the wrong
 * order, and why it is here first.
 */
class RetriesTest {

    private static final String KEY = "pointer";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String content() throws IOException {
        return store.readVersioned(KEY).map(v -> new String(v.content(), StandardCharsets.UTF_8)).orElse(null);
    }

    @Test
    void a_lost_race_is_retried_against_a_fresh_read_and_the_mutation_runs_again() throws IOException {
        store.writeVersioned(KEY, bytes("a"), null);
        FaultInjectingStore racing = FaultInjectingStore.wrap(store);
        racing.conflictNext(FaultInjectingStore.keyContaining(KEY));
        AtomicInteger asked = new AtomicInteger();
        Retries.update(racing, KEY, current -> {
            asked.incrementAndGet();
            return bytes(new String(current.orElseThrow().content(), StandardCharsets.UTF_8) + "x");
        });
        assertThat(content()).isEqualTo("ax");
        assertThat(asked.get()).as("the mutation ran once per try, over a fresh read each time").isEqualTo(2);
        assertThat(racing.calls(Op.READ_VERSIONED)).as("every try re-reads the key").isGreaterThanOrEqualTo(2);
    }

    @Test
    void exhaustion_throws_by_name_or_answers_false_by_choice() throws IOException {
        store.writeVersioned(KEY, bytes("a"), null);
        FaultInjectingStore losing = FaultInjectingStore.wrap(store);
        for (int lost = 0; lost < Retries.COMPARE_AND_SET; lost++) {
            losing.conflictNext(FaultInjectingStore.keyContaining(KEY));
        }
        assertThatThrownBy(() -> Retries.update(losing, KEY, _ -> bytes("b")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(KEY)
                .hasMessageContaining(String.valueOf(Retries.COMPARE_AND_SET));
        assertThat(losing.calls(Op.WRITE_VERSIONED)).as("every try was spent").isEqualTo(Retries.COMPARE_AND_SET);
        assertThat(content()).as("nothing landed").isEqualTo("a");

        for (int lost = 0; lost < Retries.COMPARE_AND_SET; lost++) {
            losing.conflictNext(FaultInjectingStore.keyContaining(KEY));
        }
        assertThat(Retries.tryUpdate(losing, KEY, _ -> bytes("b"))).as("the quiet ending answers false").isFalse();
        assertThat(content()).isEqualTo("a");
    }

    @Test
    void a_null_body_keeps_the_key_and_counts_as_landed() throws IOException {
        store.writeVersioned(KEY, bytes("a"), null);
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        Retries.update(counting, KEY, _ -> null);
        assertThat(Retries.tryUpdate(counting, KEY, _ -> null)).isTrue();
        assertThat(counting.calls(Op.WRITE_VERSIONED)).as("nothing to change writes nothing").isZero();
        assertThat(content()).isEqualTo("a");
    }

    @Test
    void a_decision_writes_keeps_or_deletes_and_hands_back_the_value_of_the_try_that_landed() throws IOException {
        // write: the value attached to the try that landed comes back - here what the key held before, the shape
        // Publication.link needs to tell a first link from a replacement.
        Optional<byte[]> replaced = Retries.decide(store, KEY, current -> Retries.Verdict.write(bytes("v1"),
                current.map(ArtifactStore.Versioned::content)));
        assertThat(replaced).as("nothing was there before the first write").isEmpty();
        Optional<byte[]> second = Retries.decide(store, KEY, current -> Retries.Verdict.write(bytes("v2"),
                current.map(ArtifactStore.Versioned::content)));
        assertThat(second.map(b -> new String(b, StandardCharsets.UTF_8))).contains("v1");
        assertThat(content()).isEqualTo("v2");

        // keep: nothing written, the result answered at once
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        String kept = Retries.decide(counting, KEY, _ -> Retries.Verdict.keep("unchanged"));
        assertThat(kept).isEqualTo("unchanged");
        assertThat(counting.calls(Op.WRITE_VERSIONED)).isZero();

        // delete: the key goes, the result is answered
        boolean deleted = Retries.decide(store, KEY, _ -> Retries.Verdict.delete(true));
        assertThat(deleted).isTrue();
        assertThat(store.readVersioned(KEY)).as("a delete verdict removes the key").isEmpty();
    }

    @Test
    void a_decision_that_loses_every_try_throws_or_answers_empty() throws IOException {
        store.writeVersioned(KEY, bytes("a"), null);
        FaultInjectingStore losing = FaultInjectingStore.wrap(store);
        for (int lost = 0; lost < Retries.COMPARE_AND_SET; lost++) {
            losing.conflictNext(FaultInjectingStore.keyContaining(KEY));
        }
        assertThatThrownBy(() -> Retries.decide(losing, KEY, _ -> Retries.Verdict.write(bytes("b"), 1)))
                .isInstanceOf(IOException.class).hasMessageContaining(KEY);
        for (int lost = 0; lost < Retries.COMPARE_AND_SET; lost++) {
            losing.conflictNext(FaultInjectingStore.keyContaining(KEY));
        }
        assertThat(Retries.tryDecide(losing, KEY, _ -> Retries.Verdict.write(bytes("b"), 1)))
                .as("exhaustion is empty; a landed verdict, even one carrying null, is not").isEmpty();
        assertThat(content()).isEqualTo("a");
    }

    @Test
    void a_landed_verdict_carrying_null_is_not_exhaustion() throws IOException {
        // The first sweep onto decide threw "lost the compare-and-set" for every verdict whose result was null - a
        // re-publish that is not the first, a union that added nothing, a VEX entry written with nothing to say -
        // because the landed value came back as an empty Optional and the throwing form read that as exhaustion.
        String written = Retries.decide(store, KEY, _ -> Retries.Verdict.write(bytes("v"), null));
        assertThat(written).isNull();
        assertThat(content()).isEqualTo("v");
        String kept = Retries.decide(store, KEY, _ -> Retries.Verdict.keep(null));
        assertThat(kept).isNull();
        assertThat(Retries.tryDecide(store, KEY, _ -> Retries.Verdict.keep(null)))
                .as("landed, carrying null: present, unlike exhaustion").isPresent();
    }

    @Test
    void a_decision_lost_once_is_asked_again_over_what_the_key_holds_then() throws IOException {
        store.writeVersioned(KEY, bytes("1"), null);
        FaultInjectingStore racing = FaultInjectingStore.wrap(store);
        racing.conflictNext(FaultInjectingStore.keyContaining(KEY));
        List<String> seen = new ArrayList<>();
        int result = Retries.decide(racing, KEY, current -> {
            String held = new String(current.orElseThrow().content(), StandardCharsets.UTF_8);
            seen.add(held);
            return Retries.Verdict.write(bytes(held + "+"), seen.size());
        });
        assertThat(seen).as("the decision saw the key on both tries").containsExactly("1", "1");
        assertThat(result).as("the result is the landing try's, not the first try's").isEqualTo(2);
        assertThat(content()).isEqualTo("1+");
    }
}
