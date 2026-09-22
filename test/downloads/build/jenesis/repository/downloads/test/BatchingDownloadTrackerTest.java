package build.jenesis.repository.downloads.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.downloads.BatchingDownloadTracker;
import build.jenesis.repository.inventory.DownloadTracker.Hit;
import build.jenesis.repository.inventory.DownloadTrackerProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.FaultInjectingStore.Op;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link BatchingDownloadTracker} in isolation: it accumulates successful reads off the request path, one accumulator
 * per coordinate version, and flushes each at most once per flush interval - one write carrying the hits since the
 * last flush and the newest of them - so the store is never churned and the marker does not walk. The first hit of
 * a coordinate lands at once; the hits after it are held for the interval; an idle worker flushes a due delta on its
 * own clock; a clean close flushes every residual. Every case drives {@link BatchingDownloadTracker#drain drain(batch,
 * now)} and {@link BatchingDownloadTracker#onIdle onIdle(now)} synchronously with an injected {@link Instant} - the
 * class's own time seam - so the interval is asserted without a wall clock or the worker thread. The write lands in
 * and reads back from a real {@link StoreRepositoryInventory} over a filesystem {@link ArtifactStore} - this module
 * path carries no consolidated metadata store, so it is the {@code downloaded/} sidecar and each flush is one
 * compare-and-set write the wrapping {@link FaultInjectingStore} counts.
 */
class BatchingDownloadTrackerTest {

    private static final String ECO = "maven";
    private static final String COORD = "com.example:app";
    private static final String VERSION = "1.0.0";
    private static final Duration INTERVAL = Duration.ofHours(6);

    private static final Instant T0 = Instant.parse("2026-04-01T08:00:00Z");

    @TempDir
    Path root;

    private FaultInjectingStore store;
    private DownloadTrackerProvider.Inventories inventories;
    private BatchingDownloadTracker tracker;

    @BeforeEach
    void setUp() {
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        store = FaultInjectingStore.wrap(backend);
        // The tracker writes every hit into the same store, so one FaultInjectingStore counts every flush.
        inventories = (tenant, repository) -> new StoreRepositoryInventory(store);
        tracker = new BatchingDownloadTracker(inventories, true, INTERVAL);
    }

    private Hit hit(String coordinate, String version) {
        return new Hit("default", "releases", ECO, coordinate, version);
    }

    private Optional<Instant> lastDownloaded(String coordinate, String version) throws IOException {
        return new StoreRepositoryInventory(store).lastDownloaded(ECO, coordinate, version);
    }

    private long writes() {
        return store.calls(Op.WRITE_VERSIONED);
    }

    @Test
    void the_first_hit_of_a_coordinate_lands_at_once_as_one_write() throws IOException {
        tracker.drain(List.of(hit(COORD, VERSION)), T0);

        assertThat(lastDownloaded(COORD, VERSION)).as("the first hit persists the marker without waiting").contains(T0);
        assertThat(writes()).as("one hit is one compare-and-set write").isEqualTo(1);
    }

    @Test
    void hits_inside_the_interval_are_held_and_the_marker_is_not_churned() throws IOException {
        tracker.drain(List.of(hit(COORD, VERSION)), T0);
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plus(Duration.ofHours(1)));
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plus(Duration.ofHours(2)));

        assertThat(writes()).as("inside the interval the hits accumulate in memory").isEqualTo(1);
        assertThat(lastDownloaded(COORD, VERSION)).as("the marker is not walked by held hits").contains(T0);
    }

    @Test
    void once_the_interval_has_passed_the_held_hits_land_in_one_write_carrying_the_newest() throws IOException {
        tracker.drain(List.of(hit(COORD, VERSION)), T0);
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plus(Duration.ofHours(2)));
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plus(INTERVAL));

        assertThat(writes()).as("the interval passed: one more write for every hit since the first").isEqualTo(2);
        assertThat(lastDownloaded(COORD, VERSION)).as("carrying the newest hit").contains(T0.plus(INTERVAL));
    }

    @Test
    void an_idle_worker_flushes_a_due_delta_without_a_new_hit() throws IOException {
        tracker.drain(List.of(hit(COORD, VERSION)), T0);
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plus(Duration.ofHours(2)));
        tracker.onIdle(T0.plus(Duration.ofHours(3)));
        assertThat(writes()).as("not due yet: idling changes nothing").isEqualTo(1);

        tracker.onIdle(T0.plus(INTERVAL));

        assertThat(writes()).as("due: the idle sweep flushes without waiting for a hit").isEqualTo(2);
        assertThat(lastDownloaded(COORD, VERSION)).contains(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void duplicate_hits_in_a_single_drain_coalesce_to_one_write() throws IOException {
        tracker.drain(List.of(hit(COORD, VERSION), hit(COORD, VERSION), hit(COORD, VERSION)), T0);

        assertThat(writes()).as("three hits of one coordinate are one delta of three, one write").isEqualTo(1);
        assertThat(lastDownloaded(COORD, VERSION)).contains(T0);
    }

    @Test
    void each_distinct_coordinate_flushes_on_its_own() throws IOException {
        tracker.drain(List.of(hit("com.example:a", "1.0.0"),
                hit("com.example:b", "1.0.0"),
                hit("com.example:c", "1.0.0")), T0);

        assertThat(writes()).as("each distinct pending coordinate is flushed").isEqualTo(3);
        assertThat(lastDownloaded("com.example:a", "1.0.0")).contains(T0);
        assertThat(lastDownloaded("com.example:b", "1.0.0")).contains(T0);
        assertThat(lastDownloaded("com.example:c", "1.0.0")).contains(T0);
    }

    @Test
    void a_clean_close_flushes_the_residual_delta_interval_or_not() throws IOException {
        tracker.drain(List.of(hit(COORD, VERSION)), T0);
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plus(Duration.ofHours(1)));
        assertThat(writes()).isEqualTo(1);

        tracker.close();    // never started: terminated, so the residual is flushed now

        assertThat(writes()).as("a clean stop forfeits no accepted hit").isEqualTo(2);
        assertThat(lastDownloaded(COORD, VERSION)).contains(T0.plus(Duration.ofHours(1)));
    }

    @Test
    void no_interval_flushes_on_every_drain() throws IOException {
        BatchingDownloadTracker eager = new BatchingDownloadTracker(inventories, true, null);
        eager.drain(List.of(hit(COORD, VERSION)), T0);
        eager.drain(List.of(hit(COORD, VERSION)), T0.plusSeconds(1));

        assertThat(writes()).as("0 or off: a write per drain that has hits").isEqualTo(2);
        assertThat(lastDownloaded(COORD, VERSION)).contains(T0.plusSeconds(1));
    }

    @Test
    void the_accumulators_are_bounded_by_the_coordinates_hit_within_one_interval() throws IOException {
        List<Hit> many = new ArrayList<>();
        for (int artifact = 0; artifact < 500; artifact++) {
            many.add(hit("com.example:lib" + artifact, "1.0.0"));
        }
        tracker.drain(many, T0);
        assertThat(tracker.tracked()).as("every coordinate hit this interval is held").isEqualTo(500);

        // An interval later only one coordinate is hit: the 500 flushed, idle accumulators are dropped, not kept
        // forever - the map is bounded by one interval's distinct coordinates, not every artifact ever downloaded.
        tracker.drain(List.of(hit("com.example:lib0", "1.0.0")), T0.plus(INTERVAL));
        assertThat(tracker.tracked()).as("the idle ones are gone; the one just hit remains").isEqualTo(1);
    }

    @Test
    void a_failed_flush_is_counted_folded_into_dropped_and_retried_once_the_store_heals() throws IOException {
        // The swallowed-write-failure / silent-retention-decay guard: the flush fails with an IOException (a store
        // that persists nothing), which the tracker must fold into writeFailures()/dropped() rather than swallow.
        // Without that fold the tracker reports alive && dropped==0 while writing nothing, and the not-downloaded-for
        // retention silently ages every artifact toward eviction, unnoticed on the health surface.
        store.failEveryOn(Op.WRITE_VERSIONED, FaultInjectingStore.anyKey());
        tracker.drain(List.of(hit(COORD, VERSION)), T0);
        assertThat(writes()).as("the write was attempted").isGreaterThanOrEqualTo(1);
        assertThat(lastDownloaded(COORD, VERSION)).as("the failed write persisted no marker").isEmpty();
        assertThat(tracker.writeFailures()).as("the IOException is counted").isEqualTo(1);
        assertThat(tracker.dropped()).as("folded into dropped() so a non-persisting store shows on health").isEqualTo(1);

        // Once the store heals the delta is still pending and still due - a failed flush never arms the interval -
        // so the next drain lands it.
        store.heal();
        tracker.drain(List.of(hit(COORD, VERSION)), T0.plusSeconds(1));
        assertThat(lastDownloaded(COORD, VERSION)).as("retried, not lost").contains(T0.plusSeconds(1));
    }
}
