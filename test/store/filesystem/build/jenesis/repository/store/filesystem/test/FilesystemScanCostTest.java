package build.jenesis.repository.store.filesystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging a tree by {@code scan} reads the directories on each page's way, not the tree under the prefix: draining a
 * prefix of many folders costs about one read per folder however many pages it takes, and one page costs a handful.
 * A sweep pages a project of hundreds of thousands of entries this way, and a scan that walked the whole prefix per
 * page made the sweep's cost the square of the project.
 */
class FilesystemScanCostTest {

    private static final int FOLDERS = 60;
    private static final int FILES = 50;
    private static final int PAGE = 100;

    @Test
    void draining_a_tree_reads_each_folder_about_once_and_a_page_a_handful(@TempDir Path root) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        List<String> written = new ArrayList<>();
        for (int folder = 0; folder < FOLDERS; folder++) {
            for (int file = 0; file < FILES; file++) {
                String key = "project/%03d/%03d".formatted(folder, file);
                store.write(key, new ByteArrayInputStream(new byte[]{1}));
                written.add(key);
            }
        }

        List<String> scanned = new ArrayList<>();
        long reads = 0;
        long widest = 0;
        int pages = 0;
        String cursor = "";
        while (true) {
            ArtifactStore.Scan scan = store.scan("project", cursor, PAGE, listed -> scanned.add(listed.key()));
            pages++;
            reads += scan.steps();
            widest = Math.max(widest, scan.steps());
            if (scan.cursor().isEmpty()) {
                break;
            }
            cursor = scan.cursor().get();
        }

        assertThat(scanned).as("every key, once, in key order").isEqualTo(written);
        assertThat(pages).as("a page that holds the last key says so, rather than a page that holds none")
                .isEqualTo(FOLDERS * FILES / PAGE);
        assertThat(widest).as("a page reads the prefix, the folders it spans and the one past it")
                .isLessThanOrEqualTo(2 + PAGE / FILES + 1);
        assertThat(reads).as("a drain reads each folder about once and a page the prefix and the folders it spans "
                + "- not the tree a page").isLessThanOrEqualTo(FOLDERS + 3L * pages);
    }

    /** A scan reclaims a stale write temp in every directory it reads, so a temp left in the last folder survives a
     *  first page exactly when that page did not read the tree under the prefix. */
    @Test
    void a_first_page_does_not_read_the_folders_past_it(@TempDir Path root) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        for (int folder = 0; folder < FOLDERS; folder++) {
            store.write("project/%03d/000".formatted(folder), new ByteArrayInputStream(new byte[]{1}));
        }
        Path stale = root.resolve("project").resolve("%03d".formatted(FOLDERS - 1)).resolve(".upload-stale.tmp");
        Files.write(stale, new byte[]{1});
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        store.scan("project", "", 5, _ -> {
        });
        assertThat(stale).as("the first page read five folders, not the one at the end").exists();

        String cursor = "";
        while (true) {
            ArtifactStore.Scan scan = store.scan("project", cursor, 5, _ -> {
            });
            if (scan.cursor().isEmpty()) {
                break;
            }
            cursor = scan.cursor().get();
        }
        assertThat(stale).as("while a drain reads every folder, and reclaims the stale temp in passing").doesNotExist();
    }
}
