package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * the inventory's subtree sweeps <b>page</b> every level instead of listing it whole.
 *
 * <p>Both sweeps under test used to descend by calling {@code ArtifactStore.list(level)} once per level - the release
 * enumeration through a self-recursive {@code walk}, the walk-less roll-up through a self-recursive {@code rollUp} -
 * which materialises a whole container in heap in one round-trip however wide it is. They now drive the shared
 * {@code PagedTreeWalk}, which consumes the store exclusively through {@code ArtifactStore.page}.
 *
 * <p>{@link LevelBoundedStore} is what turns that from an invisible memory property into a pass/fail one: it refuses
 * a whole-level {@code list()} past a small width, exactly as the store SPI's own inherited paging fallback refuses
 * past {@code MAX_INHERITED_CHILDREN}. Seeded with more versions than that width, every assertion here fails on the
 * pre-code (the refusal escapes out of the sweep) and passes on the paged one.
 */
class WideLevelWalkTest {

    /** Comfortably past {@link LevelBoundedStore#MAX_LEVEL}, so the coordinate's version level cannot be listed whole. */
    private static final int VERSIONS = LevelBoundedStore.MAX_LEVEL * 3;

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        Publication publication = new Publication(backend);
        StoreRepositoryInventory recording = new StoreRepositoryInventory(backend);
        for (int index = 0; index < VERSIONS; index++) {
            String version = String.format("1.%02d", index);
            byte[] payload = new byte[10 + index];
            Arrays.fill(payload, (byte) 'x');
            String hash = publication.storeBlob(new ByteArrayInputStream(payload));
            publication.link("/test/grp/lib/" + version + "/lib-" + version + ".bin", hash);
            recording.record("test", "grp/lib", version, Instant.now());
        }
        store = new LevelBoundedStore(backend);
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void the_release_enumeration_pages_a_wide_version_level() throws IOException {
        assertThat(inventory().releases())
                .as("every published version is enumerated, none lost to a level too wide to list whole")
                .hasSize(VERSIONS);
    }

    @Test
    void the_coordinate_census_pages_a_wide_version_level() throws IOException {
        List<StoreRepositoryInventory.Coordinate> coordinates = new ArrayList<>();
        inventory().coordinates(coordinates::add);

        assertThat(coordinates).hasSize(VERSIONS);
        assertThat(coordinates).allMatch(coordinate -> coordinate.coordinate().equals("grp/lib"));
    }

    @Test
    void the_subtree_size_rollup_pages_a_wide_version_level() throws IOException {
        long expected = 0;
        for (int index = 0; index < VERSIONS; index++) {
            expected += 10 + index;
        }

        long total = inventory().rollUpSizes();

        assertThat(total).as("the roll-up folds every blob beneath the coordinate, not a listable prefix of them")
                .isEqualTo(expected);
        assertThat(inventory().subtreeSize("/test/grp/lib")).hasValue(expected);
        assertThat(inventory().subtreeSize("")).hasValue(expected);
    }

    @Test
    void the_browse_container_probe_asks_one_child_of_a_wide_level_rather_than_listing_it() throws IOException {
        // the screened browse page is bounded everywhere except its container predicate, which was
        // `!store.list(child).isEmpty()` - a whole-namespace listing to answer an emptiness question, run once per
        // ROW of the page. Browsing the coordinate's parent draws one folder row for `lib`, whose version level holds
        // more children than this store will hand back as one list: the old predicate is refused by name here, the
        // one-child probe is not.
        StoreRepositoryInventory.ChildPage page =
                inventory().children("/test/grp", 100, build.jenesis.repository.store.ServableNames.Policy.HIDE_WITHHELD);

        assertThat(page.names()).as("the wide coordinate renders as one folder row, probed by one child")
                .containsExactly("lib");
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void the_pin_sweep_pages_a_wide_version_level() throws IOException {
        StoreRepositoryInventory inventory = inventory();
        for (int index = 0; index < VERSIONS; index++) {
            inventory.pin("test", "grp/lib", String.format("1.%02d", index));
        }

        assertThatCode(inventory::pinned).doesNotThrowAnyException();
        assertThat(inventory.pinned()).hasSize(VERSIONS);
    }
}
