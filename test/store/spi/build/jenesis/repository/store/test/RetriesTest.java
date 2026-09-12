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

    /**
     * A write that landed and was reported lost is recognised as landed, and the mutation does not run again.
     *
     * <p><b>The shape this defends against.</b> Between the caller and an object store sits an SDK that retries. A
     * conditional PUT that succeeds at the server and whose response is lost in transit is sent again; the second
     * attempt fails its precondition, because the first already moved the ETag; and the store reports a lost
     * compare-and-set for a write that happened. Without the re-read the caller believes it, re-reads, recomputes
     * against its own bytes and writes again - a whole read-write cycle spent to reach the state the key is
     * already in.
     *
     * <p>Measured 2026-09-11/12 by {@code StoreOperationsE2ETest}'s two-size claim: a serial publish - no peer,
     * nothing it could honestly lose to - paying twelve extra read-write pairs on azure-blob in one lane, eleven on
     * s3 two lanes later, none on the filesystem in any of them. Reads and writes rose by exactly the same amount,
     * which is what a caller-level retry costs and what distinguishes this from a probe or a larger document.
     *
     * <p><b>What the re-read asks.</b> Not "are these my bytes" - which cannot be answered, because a peer
     * writing the identical body leaves the key looking exactly the same - but "does this mutation still have
     * anything to do". A mutation whose re-application is a fixed point is settled either way, and that covers
     * every path the replay cost was measured on. One that is not says so, and is retried; the sibling below is
     * that case, and it is a deliberate limit rather than an oversight.
     *
     * <p>The sibling above is the honest loss: nothing was written, the mutation must run again, and it does.
     */
    @Test
    void a_write_that_landed_and_was_reported_lost_is_recognised_by_its_fixed_point() throws IOException {
        store.writeVersioned(KEY, bytes("a"), null);
        FaultInjectingStore replaying = FaultInjectingStore.wrap(store);
        replaying.conflictAfterNext(FaultInjectingStore.keyContaining(KEY));
        AtomicInteger asked = new AtomicInteger();
        long replayed = Retries.replayed();

        Retries.update(replaying, KEY, _ -> {
            asked.incrementAndGet();
            return bytes("b");
        });

        assertThat(content()).as("the write that landed stands").isEqualTo("b");
        assertThat(replaying.calls(Op.WRITE_VERSIONED)).as("no second write was attempted").isEqualTo(1);
        assertThat(asked.get())
                .as("asked twice: once to compute the body, once to ask the key what is left to do")
                .isEqualTo(2);
        assertThat(Retries.replayed() - replayed).as("counted as a replay rather than a loss").isEqualTo(1);
    }

    /**
     * A mutation whose re-application is not a fixed point is retried, and double-applies on a phantom loss.
     *
     * <p>This is the bound on the check above, asserted so that nobody removes it by "improving" the comparison.
     * The evidence that separates "my conditional PUT landed and the response was lost" from "a peer wrote the
     * same bytes" is not in the store: both leave one key holding one body. So the loop cannot decide it, and the
     * two ways of being wrong are not equal. Guessing that the write landed silently drops the caller's
     * contribution - an accumulation loses a delta, a claim is granted to a node that lost it. Guessing that it
     * did not costs a second application of something the caller already declared repeatable, because a
     * compare-and-set loop may ask again at any time.
     *
     * <p>So the loop errs toward asking again, which is also exactly what it did before the check existed: this
     * adds a repair where it is sound and changes nothing where it is not.
     */
    @Test
    void a_mutation_that_is_not_a_fixed_point_is_retried_rather_than_guessed() throws IOException {
        store.writeVersioned(KEY, bytes("a"), null);
        FaultInjectingStore replaying = FaultInjectingStore.wrap(store);
        replaying.conflictAfterNext(FaultInjectingStore.keyContaining(KEY));
        long lost = Retries.lost();

        Retries.update(replaying, KEY, current ->
                bytes(new String(current.orElseThrow().content(), StandardCharsets.UTF_8) + "x"));

        assertThat(content())
                .as("appended again: nothing in the store can say the first append was this caller's")
                .isEqualTo("axx");
        assertThat(Retries.lost() - lost)
                .as("not settled - the re-application asks for bytes the key does not hold")
                .isEqualTo(1);
    }

    /**
     * A claim that meets its own stamp on the re-read is told the claim stands, not handed the win it computed.
     *
     * <p>A claim - a stamp a node writes to take a rebuild - is the shape where "someone wrote these bytes" and
     * "I wrote these bytes" differ most sharply, because two nodes entering within one millisecond compute the
     * same stamp. Answering the verdict the first application produced would hand both of them the rebuild. So a
     * re-application that <em>keeps</em> is answered as it stands: it is the decision, asked of reality, saying
     * there is nothing to write.
     *
     * <p>The cost is deliberate and is the safe direction. Here the write really did land, so this caller is being
     * told it lost a claim it won - a rebuild that waits for a staleness takeover instead of starting now. That is
     * a liveness cost. The other direction is two nodes rebuilding one identity at once, which is not.
     */
    @Test
    void a_claim_that_meets_its_own_stamp_is_told_the_claim_stands() throws IOException {
        FaultInjectingStore colliding = FaultInjectingStore.wrap(store);
        colliding.conflictAfterNext(FaultInjectingStore.keyContaining(KEY));

        Optional<Retries.Verdict<String>> verdict = Retries.tryDecide(colliding, KEY, current -> current.isEmpty()
                ? Retries.Verdict.write(bytes("stamp"), "mine")
                : Retries.Verdict.keep("in flight elsewhere"));

        assertThat(verdict.orElseThrow().result())
                .as("the re-application sees a claim standing and keeps; the first application's win is not restored")
                .isEqualTo("in flight elsewhere");
    }

    /**
     * A replayed write answers the state it replaced, not the state its own write left behind.
     *
     * <p>The other half of the verdict question, and the reason a settled write answers the verdict that was
     * <em>tried</em> rather than the one the re-application produced. A pointer write carries the prior it
     * replaced back to its caller, and a caller folding that transition - or firing an event that hangs off the
     * pointer being fresh - needs the state this attempt read. Answering the re-application's verdict would report
     * a fresh write as an overwrite of itself, which silently drops the freshness.
     */
    @Test
    void a_replayed_write_answers_the_state_it_replaced() throws IOException {
        FaultInjectingStore replaying = FaultInjectingStore.wrap(store);
        replaying.conflictAfterNext(FaultInjectingStore.keyContaining(KEY));

        Optional<ArtifactStore.Versioned> prior = Retries.decide(replaying, KEY,
                current -> Retries.Verdict.write(bytes("sha-1"), current));

        assertThat(prior).as("a fresh write stays fresh: the event hanging off freshness must still fire").isEmpty();
        assertThat(content()).isEqualTo("sha-1");
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
