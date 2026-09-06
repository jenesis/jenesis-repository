package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DirtyFlag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DirtyFlag}, the small object a feed consumer keeps beside the feed (its pass cadence is a {@link build.jenesis.repository.store.StoredCounter}). A flag is
 * raised by any change and lowered only against the token read before the pass, so a change landing mid-pass
 * survives; a counter says when the periodic full pass is due and starts over when it has run.
 */
class DirtyFlagTest {

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
}
