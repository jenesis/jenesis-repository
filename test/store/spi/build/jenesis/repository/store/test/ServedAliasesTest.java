package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ServedAliases;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record that one served path is the same file as another, under a second name.
 *
 * <p>It exists because a cross-published modular jar serves under a Maven coordinate <em>and</em> under two
 * {@code /module/} views over one blob, and nothing else in the store says the three names are one artifact - so a
 * reviewer releasing the coordinate left the views held. Both cheaper signals were measured and are unsound: distinct
 * files of one version routinely share a content hash, and a version's other files are not that file renamed.
 *
 * <p>The legs below are the two the relation can actually get wrong, and each is written so that removing the code it
 * covers reddens it.
 */
class ServedAliasesTest {

    private static final String ONE = "/maven/org/example/widget/1.0/widget-1.0.jar";
    private static final String TWO = "/maven/org/example/widget/2.0/widget-2.0.jar";
    private static final String VERSIONED = "/module/test.widget/1.0/test.widget.jar";
    private static final String LATEST = "/module/test.widget/test.widget.jar";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_path_that_cross_publishes_nothing_is_a_group_of_itself() throws IOException {
        assertThat(ServedAliases.group(store, ONE))
                .as("the overwhelmingly common case - nothing cross-publishes a wheel or a .deb - so this has to be "
                        + "answerable without a record and without an enumeration")
                .containsExactly(ONE);
    }

    @Test
    void every_name_of_one_artifact_resolves_to_the_same_group() throws IOException {
        ServedAliases.record(store, ONE, VERSIONED);
        ServedAliases.reassign(store, ONE, LATEST);

        assertThat(ServedAliases.group(store, ONE)).containsExactlyInAnyOrder(ONE, VERSIONED, LATEST);
        assertThat(ServedAliases.group(store, VERSIONED))
                .as("handed an alias rather than the origin, a release still has to reach the whole artifact")
                .containsExactlyInAnyOrder(ONE, VERSIONED, LATEST);
    }

    /**
     * The moving alias, and the reason recording one is not a single operation.
     *
     * <p>{@code /module/<name>/<name>.jar} names whichever version published last, so publishing 2.0 takes it off 1.0.
     * Were it left to accumulate, releasing 1.0 would resolve a group still containing it and lift a view that has
     * been 2.0's since 2.0 landed - and 2.0 may be held on its own account, which makes that a disclosure rather than
     * an untidy record.
     */
    @Test
    void a_latest_view_belongs_to_the_version_that_published_last() throws IOException {
        ServedAliases.reassign(store, ONE, LATEST);
        ServedAliases.reassign(store, TWO, LATEST);

        assertThat(ServedAliases.group(store, ONE))
                .as("1.0 no longer owns the latest view")
                .containsExactly(ONE);
        assertThat(ServedAliases.group(store, TWO))
                .as("2.0 does")
                .containsExactlyInAnyOrder(TWO, LATEST);
    }

    /**
     * The same question asked of a store that is already inconsistent, which is what the read-time confirmation is
     * for.
     *
     * <p><b>The state is reachable, and only by a crash.</b> {@link ServedAliases#record} writes the group entry
     * first and the reverse entry second - deliberately, so that a crash between them leaves a group naming an alias
     * that cannot name it back, rather than an alias pointing at a group that does not list it. The first is a stale
     * line this read drops; the second would read as a group of one and silently lose the alias from a release. So
     * the surviving hazard is exactly this: an origin's group naming an alias whose single-valued reverse entry says
     * someone else owns it.
     *
     * <p>It cannot be built from one public call - every writer that touches a group also writes the reverse entry -
     * so the half-written pair is written here by key. That is the point of a defensive read: the state it covers is
     * one no correct sequence produces.
     */
    @Test
    void a_stale_group_line_does_not_survive_the_reverse_entry() throws IOException {
        ServedAliases.reassign(store, TWO, LATEST);
        // 1.0's group is left naming the latest view, as a crash inside record() between its two writes would leave
        // it; the reverse entry still says 2.0 owns it.
        store.write(ServedAliases.groupKey(ONE), new ByteArrayInputStream(LATEST.getBytes(StandardCharsets.UTF_8)));

        assertThat(ServedAliases.group(store, ONE))
                .as("the group is answered against the side that can only have one value, so a release of 1.0 never "
                        + "reaches an alias 2.0 owns")
                .containsExactly(ONE);
        assertThat(ServedAliases.group(store, TWO))
                .as("and 2.0 still owns it")
                .containsExactlyInAnyOrder(TWO, LATEST);
    }

    @Test
    void forgetting_a_path_drops_both_directions() throws IOException {
        ServedAliases.record(store, ONE, VERSIONED);
        ServedAliases.reassign(store, ONE, LATEST);

        ServedAliases.forget(store, ONE);

        assertThat(ServedAliases.group(store, ONE)).containsExactly(ONE);
        assertThat(ServedAliases.origin(store, VERSIONED))
                .as("no row outlives the artifact it describes, or a later release resolves a group through it")
                .isEmpty();
        assertThat(ServedAliases.origin(store, LATEST)).isEmpty();
    }

    @Test
    void recording_the_same_alias_twice_is_free() throws IOException {
        ServedAliases.record(store, ONE, VERSIONED);
        ServedAliases.record(store, ONE, VERSIONED);

        assertThat(ServedAliases.group(store, ONE))
                .as("a byte-identical republish and a repeated rebuild pass both re-record; neither may duplicate")
                .containsExactlyInAnyOrder(ONE, VERSIONED);
    }
}
