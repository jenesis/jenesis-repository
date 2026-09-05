package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DirtyFlag;
import build.jenesis.repository.store.PassCounter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DirtyFlag} and {@link PassCounter}: the two small objects a feed consumer keeps beside the feed. A flag is
 * raised by any change and lowered only against the token read before the pass, so a change landing mid-pass
 * survives; a counter says when the periodic full pass is due and starts over when it has run.
 */
class DirtyFlagAndPassCounterTest {

    private static final Instant T0 = Instant.parse("2026-09-05T09:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    @Test
    void a_flag_raised_by_many_is_one_object_and_is_lowered_only_against_the_token_read_before_the_pass()
            throws IOException {
        DirtyFlag flag = new DirtyFlag(store, "index/retract");
        assertThat(flag.peek()).as("nothing pending").isEmpty();
        flag.mark(T0);
        flag.mark(T0.plusSeconds(1));
        Optional<ArtifactStore.Versioned> before = flag.peek();
        assertThat(before).as("two changes, one flag").isPresent();

        // A pass reads the flag, walks, and meanwhile a third change re-raises it: the token moved, so the pass's
        // clear must leave the flag standing for the next pass.
        flag.mark(T0.plusSeconds(2));
        flag.clearIf(before.get().token());
        assertThat(flag.peek()).as("a change that landed mid-pass survives the pass's clear").isPresent();

        Optional<ArtifactStore.Versioned> latest = flag.peek();
        flag.clearIf(latest.get().token());
        assertThat(flag.peek()).as("lowered against the current token").isEmpty();
        flag.clearIf(latest.get().token());                       // idempotent on an absent flag
        assertThat(flag.peek()).isEmpty();
    }

    @Test
    void a_pass_counter_is_due_on_the_nth_pass_and_starts_over_after_the_full_one() throws IOException {
        PassCounter passes = new PassCounter(store, "index/passes");
        assertThat(passes.passes()).isZero();
        assertThat(passes.due(1)).as("a cadence of one: every pass reconciles").isTrue();
        assertThat(passes.due(3)).isFalse();
        passes.bump();
        assertThat(passes.due(3)).as("the second pass is not yet the third").isFalse();
        passes.bump();
        assertThat(passes.passes()).isEqualTo(2);
        assertThat(passes.due(3)).as("the third pass since the last full one reconciles").isTrue();
        passes.reset();
        assertThat(passes.passes()).isZero();
        assertThat(passes.due(3)).isFalse();

        store.writeVersioned("index/passes", "not a number".getBytes(StandardCharsets.UTF_8),
                store.readVersioned("index/passes").orElseThrow().token());
        assertThat(passes.passes()).as("an unreadable count restarts at zero").isZero();
    }
}
