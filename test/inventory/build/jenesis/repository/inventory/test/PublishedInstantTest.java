package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instant a version records, and the newest-first feed row that follows it, move when the version gains a file or
 * other bytes and stay when bytes it already holds are uploaded again: a client re-running a publish it already made
 * changes nothing a reader sees, the feed included.
 */
class PublishedInstantTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final Instant FIRST = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant LATER = FIRST.plusSeconds(5);
    private static final String JAR = "a".repeat(64);
    private static final String POM = "b".repeat(64);

    @TempDir
    Path root;

    private StoreRepositoryInventory inventory;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        inventory = new StoreRepositoryInventory(store);
    }

    @Test
    void a_re_upload_of_bytes_the_version_holds_keeps_its_instant_and_its_feed_row() throws IOException {
        inventory.record(ECO, "lib", "1.0.0", false, FIRST, JAR);
        inventory.record(ECO, "lib", "1.0.0", false, LATER, JAR);

        assertThat(inventory.publishedAt(ECO, "lib", "1.0.0")).contains(FIRST);
        assertThat(feed()).as("one row, where the first upload put it").containsExactly(FIRST);
    }

    @Test
    void another_file_of_the_version_moves_its_instant_and_its_feed_row() throws IOException {
        inventory.record(ECO, "lib", "1.0.0", false, FIRST, JAR);
        inventory.record(ECO, "lib", "1.0.0", false, LATER, POM);

        assertThat(inventory.publishedAt(ECO, "lib", "1.0.0")).contains(LATER);
        assertThat(feed()).as("one row, moved to the newest file").containsExactly(LATER);
    }

    /** The instants of the feed's rows, newest first. */
    private List<Instant> feed() throws IOException {
        return inventory.recent(null, 10).releases().stream().map(Release::published).toList();
    }
}
