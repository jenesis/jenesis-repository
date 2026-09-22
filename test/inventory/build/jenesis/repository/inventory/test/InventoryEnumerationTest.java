package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciled inventory view over the consolidated {@code meta} document: {@code coordinates()} and
 * {@code releases()} enumerate exactly the published set the {@code record}/{@code evict} mutations maintain - a newly
 * recorded version appears, an evicted one disappears, the enumeration is grouped-by-coordinate and reads the publish
 * facts (instant, prerelease, pin) back for each release, and a coordinate's own {@code versions()} lists just its
 * siblings. No pointer tree is needed: the record path writes the published section, and the store is the source of
 * truth the two enumerations agree on.
 */
class InventoryEnumerationTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void coordinates_enumerate_exactly_the_recorded_published_set() throws IOException {
        inventory().record(ECO, "com.example:alpha", "1.0.0", NOW);
        inventory().record(ECO, "com.example:alpha", "2.0.0", NOW);
        inventory().record(ECO, "com.example:beta", "1.0.0", NOW);

        assertThat(inventory().coordinates())
                .extracting(StoreRepositoryInventory.Coordinate::coordinate,
                        StoreRepositoryInventory.Coordinate::version)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("com.example:alpha", "1.0.0"),
                        org.assertj.core.groups.Tuple.tuple("com.example:alpha", "2.0.0"),
                        org.assertj.core.groups.Tuple.tuple("com.example:beta", "1.0.0"));
        assertThat(inventory().coordinates()).allSatisfy(coordinate ->
                assertThat(coordinate.ecosystem()).isEqualTo(ECO));
    }

    @Test
    void the_streaming_coordinate_visitor_delivers_exactly_the_buffered_membership() throws IOException {
        inventory().record(ECO, "com.example:alpha", "1.0.0", NOW);
        inventory().record(ECO, "com.example:alpha", "2.0.0", NOW);
        inventory().record(ECO, "com.example:beta", "1.0.0", NOW);

        // The identity rollup folds over coordinates(visitor) instead of buffering coordinates() into a List, so the
        // streamed enumeration must deliver exactly the same members - one visit per published version, no more, no
        // fewer - as the buffered form the enumeration tests pin above. That equivalence is what lets rebuildIdentity()
        // hold only its fixed-width accumulator on a repository with millions of versions.
        List<StoreRepositoryInventory.Coordinate> streamed = new ArrayList<>();
        inventory().coordinates(streamed::add);

        assertThat(streamed)
                .as("the streaming visitor sees exactly the buffered membership, in the same one-per-version count")
                .containsExactlyInAnyOrderElementsOf(inventory().coordinates());
    }

    @Test
    void releases_read_the_publish_facts_back_for_each_version() throws IOException {
        inventory().record(ECO, "com.example:lib", "1.0.0", false, NOW);
        Instant later = NOW.plus(Duration.ofDays(3));
        inventory().record(ECO, "com.example:lib", "2.0.0-SNAPSHOT", true, later);

        Collection<Release> releases = inventory().releases();
        assertThat(releases).hasSize(2);

        Release stable = releases.stream().filter(r -> r.version().equals("1.0.0")).findFirst().orElseThrow();
        assertThat(stable.ecosystem()).isEqualTo(ECO);
        assertThat(stable.coordinate()).isEqualTo("com.example:lib");
        assertThat(stable.published()).as("the recorded publish instant is read back").isEqualTo(NOW);
        assertThat(stable.prerelease()).isFalse();
        assertThat(stable.pinned()).isFalse();
        assertThat(stable.lastDownloaded()).as("never downloaded defaults to the publish instant").isEqualTo(NOW);

        Release prerelease = releases.stream().filter(r -> r.version().startsWith("2.0.0")).findFirst().orElseThrow();
        assertThat(prerelease.published()).isEqualTo(later);
        assertThat(prerelease.prerelease()).as("the format-supplied prerelease flag rides through").isTrue();
    }

    @Test
    void a_newly_recorded_version_appears_and_an_evicted_one_disappears() throws IOException {
        inventory().record(ECO, "com.example:app", "1.0.0", NOW);
        inventory().record(ECO, "com.example:app", "2.0.0", NOW);
        assertThat(versionsOf("com.example:app")).containsExactlyInAnyOrder("1.0.0", "2.0.0");

        // A third version published later shows up in the enumeration without touching the first two.
        inventory().record(ECO, "com.example:app", "3.0.0", NOW.plus(Duration.ofDays(1)));
        assertThat(versionsOf("com.example:app")).containsExactlyInAnyOrder("1.0.0", "2.0.0", "3.0.0");

        // Evicting the middle version drops it from every enumeration; its siblings remain.
        Release evicted = inventory().releases().stream()
                .filter(r -> r.version().equals("2.0.0")).findFirst().orElseThrow();
        inventory().evict(evicted);

        assertThat(versionsOf("com.example:app")).containsExactlyInAnyOrder("1.0.0", "3.0.0");
        assertThat(inventory().coordinates())
                .noneMatch(coordinate -> coordinate.version().equals("2.0.0"));
    }

    @Test
    void versions_lists_only_a_single_coordinates_siblings() throws IOException {
        inventory().record(ECO, "com.example:one", "1.0.0", NOW);
        inventory().record(ECO, "com.example:one", "1.1.0", NOW);
        inventory().record(ECO, "com.example:two", "9.9.9", NOW);

        assertThat(inventory().versions(ECO, "com.example:one"))
                .extracting(Release::version).containsExactlyInAnyOrder("1.0.0", "1.1.0");
        assertThat(inventory().versions(ECO, "com.example:two"))
                .extracting(Release::version).containsExactly("9.9.9");
        assertThat(inventory().versions(ECO, "com.example:absent")).isEmpty();
    }

    private List<String> versionsOf(String coordinate) throws IOException {
        List<String> versions = new ArrayList<>();
        for (Release release : inventory().releases()) {
            if (release.coordinate().equals(coordinate)) {
                versions.add(release.version());
            }
        }
        return versions;
    }
}
