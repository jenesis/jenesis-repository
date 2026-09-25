package build.jenesis.repository.publication.contract.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A hold survives a hold kind that is broken.
 *
 * <p>The five statics on {@link HoldReleaseObserver} fan every release, discard and still-held probe out to every
 * discovered hold kind and catch nothing, on the argument that a throwing hook must leave the hold <b>standing</b>
 * rather than silently release a quarantined artifact. That argument is load-bearing - these run on a reviewer's
 * release request and on the publish path - and it existed only as a sentence in a method comment. A promise with
 * no leg is a claim, and this one is about not releasing quarantined artifacts.
 *
 * <p>The probes are the sharp half. {@code onReleased} throwing is visible: the release fails and the reviewer sees
 * it. {@code holds} throwing is not, because the tempting containment - treat a broken hook as "no hold" - answers
 * <em>false</em>, and false is the answer that lets the artifact out. Each leg below therefore asserts the failure
 * propagates rather than that some particular state resulted.
 *
 * <p>Driven through the parameterised fan-out rather than a registered provider. A {@code provides} clause would
 * make this hostile kind visible to every other suite in the module, and a hand-fanned loop would not be testing
 * the choreography at all - it would be testing the loop the test itself wrote.
 */
class HoldReleaseFailClosedTest {

    private static final String PATH = HookTestFormat.PREFIX + "org/example/fail-closed";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** A hold kind whose every leg fails, the way a module with a broken backend behind it does. */
    private record Hostile(String kind) implements HoldReleaseObserver {

        @Override
        public void onReleased(ArtifactStore store, String path) throws IOException {
            throw new IOException("the hold kind's backend is down");
        }

        @Override
        public void onDiscarded(ArtifactStore store, String path) throws IOException {
            throw new IOException("the hold kind's backend is down");
        }

        @Override
        public boolean holds(ArtifactStore store, String path) throws IOException {
            throw new IOException("the hold kind's backend is down");
        }
    }

    private static final List<HoldReleaseObserver> HOSTILE = List.of(new Hostile("broken"));

    @Test
    void a_release_fails_rather_than_completing_without_one_hold_kind() {
        assertThatThrownBy(() -> HoldReleaseObserver.released(store, PATH, HOSTILE))
                .as("the release surface links the release pointer only after this returns, so a hook that could "
                        + "not run must stop the release rather than let it proceed half-observed")
                .isInstanceOf(IOException.class);
    }

    @Test
    void a_discard_fails_rather_than_destroying_what_a_hook_could_not_record() {
        assertThatThrownBy(() -> HoldReleaseObserver.discarded(store, PATH, HOSTILE))
                .as("a discard runs while the artifact is still there precisely so a hook can record a durable "
                        + "intent about the destruction; one that threw has recorded nothing")
                .isInstanceOf(IOException.class);
    }

    @Test
    void a_still_held_probe_fails_rather_than_answering_that_nothing_holds() {
        // The dangerous one. Containing this would answer false, and false is what releases the artifact.
        assertThatThrownBy(() -> HoldReleaseObserver.anyHolds(store, PATH, HOSTILE))
                .as("a hold kind that cannot answer is not a hold kind that answers 'no'")
                .isInstanceOf(IOException.class);
    }

    @Test
    void an_other_kind_probe_fails_rather_than_answering_that_no_other_kind_holds() {
        assertThatThrownBy(() -> HoldReleaseObserver.heldByAnotherKind(store, PATH, "kev", HOSTILE))
                .as("this is the probe an automated release of one kind uses to avoid lifting another kind's hold, "
                        + "so a broken kind must not read as an absent one")
                .isInstanceOf(IOException.class);

        assertThatThrownBy(() -> HoldReleaseObserver.heldByAnotherKind(store, HookTestFormat.ECOSYSTEM,
                Coordinates.of(PATH), HookTestFormat.VERSION, List.of(PATH), "kev", HOSTILE))
                .as("and the coordinate-keyed overload fails on the same terms as its path-keyed twin")
                .isInstanceOf(IOException.class);
    }

    @Test
    void the_healthy_path_is_unchanged_so_the_legs_above_are_not_vacuous() throws IOException {
        // Without this, every assertion above could be passing because the fan-out throws for some reason of its
        // own rather than because the hook does.
        List<String> released = new ArrayList<>();
        HoldReleaseObserver benign = new HoldReleaseObserver() {
            @Override
            public void onReleased(ArtifactStore store, String path) {
                released.add(path);
            }
        };

        HoldReleaseObserver.released(store, PATH, List.of(benign));

        assertThat(released).as("a healthy hook is fanned to and the release completes").containsExactly(PATH);
        assertThat(HoldReleaseObserver.anyHolds(store, PATH, List.of(benign)))
                .as("and with no record and no hook claiming one, nothing holds").isFalse();
    }
}
