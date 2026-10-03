package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A version is published by its first file and changed by each later one. Its first instant - what retention ages it
 * by and the release feed lists it under - never moves; another file moves only its last-changed instant; and bytes it
 * already holds, uploaded again, change nothing and write nothing.
 */
class PublishedInstantTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final Instant FIRST = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant LATER = FIRST.plusSeconds(5);
    private static final String JAR = "a".repeat(64);
    private static final String POM = "b".repeat(64);

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreRepositoryInventory inventory;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        inventory = new StoreRepositoryInventory(store);
    }

    @Test
    void another_file_of_the_version_moves_its_last_change_and_neither_its_first_publish_nor_its_feed_row()
            throws IOException {
        inventory.record(ECO, "lib", "1.0.0", false, FIRST, JAR);
        inventory.record(ECO, "lib", "1.0.0", false, LATER, POM);

        assertThat(inventory.publishedAt(ECO, "lib", "1.0.0")).contains(FIRST);
        assertThat(facts().changed()).isEqualTo(LATER);
        assertThat(feed()).as("one row, where the first file put it").containsExactly(FIRST);
    }

    @Test
    void a_re_upload_of_bytes_the_version_holds_writes_nothing() throws IOException {
        inventory.record(ECO, "lib", "1.0.0", false, FIRST, JAR);
        Object written = store.readVersioned(MetadataKey.version(ECO, "lib", "1.0.0")).orElseThrow().token();

        inventory.record(ECO, "lib", "1.0.0", false, LATER, JAR);

        assertThat(store.readVersioned(MetadataKey.version(ECO, "lib", "1.0.0")).orElseThrow().token())
                .as("the version's document is as the first upload left it").isEqualTo(written);
        assertThat(facts().changed()).isEqualTo(FIRST);
        assertThat(feed()).containsExactly(FIRST);
    }

    private PublishedSection.Facts facts() throws IOException {
        return PublishedSection.facts(MetadataDocument.read(store.readVersioned(
                MetadataKey.version(ECO, "lib", "1.0.0")).orElseThrow().content()).section(PublishedSection.TAG))
                .orElseThrow();
    }

    /** The instants of the feed's rows, newest first. */
    private List<Instant> feed() throws IOException {
        return inventory.recent(null, 10).releases().stream().map(Release::published).toList();
    }
}
