package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Requests;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cadence every feed-driven pass keeps, held on its own: every Nth pass visits every version, the passes between
 * visit only what was published since the last full one less the lookback, only a clean full pass advances the
 * stamp, and a standing request naming the task makes the next pass full - which is what a changed catalogue asks
 * for.
 */
class IncrementalPassesTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    private static final String ECO = "maven";

    @TempDir
    Path root;

    private ArtifactStore store;

    private StoreRepositoryInventory inventory;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope("app");
        inventory = new StoreRepositoryInventory(store);
    }

    @Test
    void the_first_pass_is_full_the_next_reads_only_what_was_published_since_and_every_nth_is_full_again()
            throws IOException {
        record("org.old:a", NOW.minus(Duration.ofDays(2)));
        record("org.old:b", NOW.minus(Duration.ofDays(2)));
        UnaryOperator<String> config = key -> IncrementalPasses.FULL_EVERY.equals(key) ? "2" : null;

        IncrementalPasses first = IncrementalPasses.over(store, "probe", "probe/passes", config);
        assertThat(first.full()).as("nothing has ever been visited: " + first.reason()).isTrue();
        assertThat(visited(first)).containsExactlyInAnyOrder("org.old:a", "org.old:b");
        first.completed(NOW, true);

        record("org.new:c", NOW.plus(Duration.ofMinutes(5)));
        IncrementalPasses second = IncrementalPasses.over(store, "probe", "probe/passes", config);
        assertThat(second.full()).as(second.reason()).isFalse();
        assertThat(second.since()).contains(NOW);
        assertThat(visited(second)).as("only what was published since the full pass").containsExactly("org.new:c");
        second.completed(NOW.plus(Duration.ofHours(1)), true);

        IncrementalPasses third = IncrementalPasses.over(store, "probe", "probe/passes", config);
        assertThat(third.full()).as("every second pass is full: " + third.reason()).isTrue();
        assertThat(visited(third)).containsExactlyInAnyOrder("org.old:a", "org.old:b", "org.new:c");
    }

    @Test
    void a_full_pass_that_did_not_land_clean_leaves_the_stamp_so_the_next_pass_is_full_again() throws IOException {
        record("org.old:a", NOW.minus(Duration.ofDays(2)));
        IncrementalPasses first = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        assertThat(first.full()).isTrue();
        first.completed(NOW, false);

        IncrementalPasses second = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        assertThat(second.full()).as("an unclean full pass made no claim: " + second.reason()).isTrue();
        second.completed(NOW, true);

        IncrementalPasses third = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        assertThat(third.full()).as("a clean full pass stamped, so the next is incremental").isFalse();
    }

    @Test
    void the_releases_leg_hands_over_the_release_rows_of_what_was_published_since() throws IOException {
        record("org.old:a", NOW.minus(Duration.ofDays(2)));
        IncrementalPasses first = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        List<String> all = new ArrayList<>();
        first.releases(inventory, release -> all.add(release.coordinate() + " " + release.version()));
        assertThat(all).containsExactly("org.old:a 1.0");
        first.completed(NOW, true);

        record("org.new:b", NOW.plus(Duration.ofMinutes(1)));
        IncrementalPasses second = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        List<String> since = new ArrayList<>();
        second.releases(inventory, release -> since.add(release.coordinate() + " " + release.version()));
        assertThat(since).as("the release rows, not just the coordinates, of what was published since")
                .containsExactly("org.new:b 1.0");
    }

    @Test
    void a_standing_request_naming_the_task_makes_the_next_pass_full() throws IOException {
        record("org.old:a", NOW.minus(Duration.ofDays(2)));
        IncrementalPasses first = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        first.completed(NOW, true);
        ArtifactStore deployment = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Requests.installRoot(deployment);
        Requests.request(deployment, "probe", "the catalogue changed");

        IncrementalPasses requested = IncrementalPasses.over(store, "probe", "probe/passes", key -> null);
        assertThat(requested.full()).as("a changed catalogue names old versions: " + requested.reason()).isTrue();
        assertThat(requested.reason()).contains("the catalogue changed");
        Requests.clear(deployment, "probe");
        assertThat(IncrementalPasses.over(store, "probe", "probe/passes", key -> null).full())
                .as("with the request cleared the cadence is what the count says").isFalse();
    }

    @Test
    void the_dial_degrades_to_its_default_on_garbage_and_never_below_one() {
        assertThat(IncrementalPasses.fullEvery(key -> null)).isEqualTo(IncrementalPasses.DEFAULT_FULL_EVERY);
        assertThat(IncrementalPasses.fullEvery(key -> "every six hours")).isEqualTo(IncrementalPasses.DEFAULT_FULL_EVERY);
        assertThat(IncrementalPasses.fullEvery(key -> "0")).as("1 is every pass full").isEqualTo(1);
        assertThat(IncrementalPasses.fullEvery(key -> " 7 ")).isEqualTo(7);
    }

    /**
     * A row that appears after a full pass carrying an EARLIER instant than the pass's stamp.
     *
     * <p>The stamp's claim is "everything published before this instant was visited", and a full pass enumerates
     * the published key space live and in key order - so a version whose publish instant is before the pass began
     * but whose row was written after the pass had gone by the key it sorts at was never visited, and the stamp
     * says it was. The ordinary way to get one is a publish in flight when a full pass begins, at a coordinate
     * sorting early.
     *
     * <p>Without the lookback the incremental leg visits NOTHING here, not merely "everything but the late one":
     * {@code recent} returns at the first release below the floor and the late release is the NEWEST in the recent
     * index, so the floor is met on the first row. That is what this pins, and it is why the assertion below is on
     * the whole visited list rather than on one member of it.
     */
    @Test
    void a_row_that_appears_after_a_full_pass_with_an_earlier_instant_is_visited_by_the_next_incremental_one()
            throws IOException {
        record("org.old:a", NOW.minus(Duration.ofDays(2)));
        UnaryOperator<String> config = key -> IncrementalPasses.FULL_EVERY.equals(key) ? "24" : null;

        IncrementalPasses full = IncrementalPasses.over(store, "probe", "probe/passes", config);
        assertThat(full.full()).isTrue();
        assertThat(visited(full)).containsExactly("org.old:a");
        full.completed(NOW, true);                       // "everything published before NOW was visited"

        // Thirty seconds inside the default minute: a write that outlived the pass's enumeration of its key, or a
        // publisher whose clock lags. The full pass provably never visited it - it did not exist yet.
        record("org.late:z", NOW.minus(Duration.ofSeconds(30)));

        IncrementalPasses incremental = IncrementalPasses.over(store, "probe", "probe/passes", config);
        assertThat(incremental.full()).as(incremental.reason()).isFalse();
        assertThat(visited(incremental))
                .as("the lookback carries the row that landed behind the full pass")
                .containsExactly("org.late:z");
    }

    /** The dial: what it accepts, and that switching it off restores the gap the finding describes. */
    @Test
    void the_lookback_dial_degrades_to_its_default_on_garbage_and_never_reaches_below_zero() throws IOException {
        assertThat(IncrementalPasses.lookback(_ -> null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(IncrementalPasses.lookback(_ -> "  ")).isEqualTo(Duration.ofMinutes(1));
        assertThat(IncrementalPasses.lookback(_ -> "banana")).as("garbage reads as the default, never as zero")
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(IncrementalPasses.lookback(_ -> "90s")).as("the suffixed grammar an environment variable carries")
                .isEqualTo(Duration.ofSeconds(90));
        assertThat(IncrementalPasses.lookback(_ -> "PT30S")).isEqualTo(Duration.ofSeconds(30));
        assertThat(IncrementalPasses.lookback(_ -> "-PT5M")).as("a negative window would move the floor the wrong way")
                .isEqualTo(Duration.ZERO);

        // Switched off, the row that landed behind the full pass waits for the next full pass - the behaviour the
        // finding on IncrementalPasses describes, kept reachable on purpose for a deployment that has measured the
        // cost of the window and chosen against it.
        record("org.old:a", NOW.minus(Duration.ofDays(2)));
        UnaryOperator<String> off = key -> switch (key) {
            case IncrementalPasses.FULL_EVERY -> "24";
            case IncrementalPasses.LOOKBACK -> "PT0S";
            default -> null;
        };
        IncrementalPasses full = IncrementalPasses.over(store, "probe", "probe/passes", off);
        assertThat(full.full()).isTrue();
        full.completed(NOW, true);
        record("org.late:z", NOW.minus(Duration.ofSeconds(30)));
        assertThat(visited(IncrementalPasses.over(store, "probe", "probe/passes", off)))
                .as("with the window off the leg returns at the first row below the floor, having visited none")
                .isEmpty();
    }

    private void record(String coordinate, Instant published) throws IOException {
        inventory.record(ECO, coordinate, "1.0", false, published);
    }

    private List<String> visited(IncrementalPasses cadence) throws IOException {
        List<String> visited = new ArrayList<>();
        cadence.coordinates(inventory, coordinate -> visited.add(coordinate.coordinate()));
        return visited;
    }
}
