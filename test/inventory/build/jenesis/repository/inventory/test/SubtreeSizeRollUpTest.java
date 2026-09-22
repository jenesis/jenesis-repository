package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subtree-size roll-up folds each published artifact blob's recorded size into every browse folder's total, so the
 * console reads a folder's size with one direct key lookup instead of re-walking the tree. Over a small publish tree
 * with blobs of known size, a folder's rolled-up total is the sum of every blob beneath it, the repository root total
 * is the whole sum (and the return value of the sweep), and a folder that was never rolled up reads empty rather than
 * zero (so a browse never confuses "not computed yet" with "empty"). Read-first (PRINCIPLES §7): the sweep does the
 * work, the reader recomputes nothing.
 */
class SubtreeSizeRollUpTest {

    private static final int LIB_1 = 100;
    private static final int LIB_2 = 250;
    private static final int TOOL = 70;
    private static final long TOTAL = LIB_1 + LIB_2 + TOOL;

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        Publication publication = new Publication(store);
        publish(publication, "/test/grp/lib/1.0/lib-1.0.bin", LIB_1);
        publish(publication, "/test/grp/lib/2.0/lib-2.0.bin", LIB_2);
        publish(publication, "/test/grp/tool/1.0/tool-1.0.bin", TOOL);
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void a_folder_total_is_the_sum_of_every_blob_beneath_it() throws IOException {
        long total = inventory().rollUpSizes();

        assertThat(total).as("the sweep returns the whole repository total").isEqualTo(TOTAL);
        assertThat(inventory().subtreeSize("/test/grp/lib")).hasValue(LIB_1 + LIB_2);
        assertThat(inventory().subtreeSize("/test/grp/tool")).hasValue(TOOL);
        assertThat(inventory().subtreeSize("/test/grp")).as("a parent sums its child folders").hasValue(TOTAL);
        assertThat(inventory().subtreeSize("/test")).hasValue(TOTAL);
        assertThat(inventory().subtreeSize("")).as("the repository root is the whole tree").hasValue(TOTAL);
        assertThat(inventory().subtreeSize("/test/grp/lib/1.0")).as("a version folder holds its one blob").hasValue(LIB_1);
    }

    @Test
    void a_leading_slash_is_optional_when_reading_a_folder_size() throws IOException {
        inventory().rollUpSizes();

        assertThat(inventory().subtreeSize("test/grp/lib"))
                .as("the browse convention resolves the same roll-up object with or without the leading slash")
                .isEqualTo(inventory().subtreeSize("/test/grp/lib"));
    }

    @Test
    void a_folder_never_rolled_up_reads_empty_rather_than_zero() throws IOException {
        assertThat(inventory().subtreeSize("/test/grp/lib"))
                .as("no sweep has run - not computed, not empty").isEmpty();
        assertThat(inventory().subtreeSize("/does/not/exist")).isEmpty();
    }

    private static void publish(Publication publication, String path, int size) throws IOException {
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        String hash = publication.storeBlob(new ByteArrayInputStream(payload));
        publication.link(path, hash);
    }
}
