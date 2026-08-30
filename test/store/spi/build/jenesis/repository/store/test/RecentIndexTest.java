package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.RecentIndex;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The newest-first index: the mechanism two roll-up indexes share, over a real filesystem store.
 *
 * <p>What is under test is that the <em>key</em> carries the order, because that is the whole reason this type
 * exists. A roll-up keyed by its entry's own id - a content hash, a scan id - is in no useful order, so "the newest
 * first" means reading every row and sorting, and paging an arbitrary order returns an arbitrary subset rather than
 * the newest. These tests pin the property that makes one bounded page both correct and cheap.
 */
public class RecentIndexTest {

    private static final String ROOT = "recent";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private RecentIndex index() {
        return new RecentIndex(store, ROOT);
    }

    private void record(String id, String when) throws IOException {
        index().record(Instant.parse(when), id, id.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> ids(RecentIndex.Page page) {
        return page.rows().stream().map(row -> new String(row.content(), StandardCharsets.UTF_8)).toList();
    }

    @Test
    void a_page_is_newest_first_regardless_of_the_order_rows_were_written_in() throws IOException {
        // Written oldest-first, middle, then newest, so a page that merely echoed insertion order would pass by
        // accident in one of the three orders and is ruled out by using none of them.
        record("middle", "2026-05-05T00:00:00Z");
        record("oldest", "2026-01-01T00:00:00Z");
        record("newest", "2026-09-09T00:00:00Z");

        assertThat(ids(index().page(null, 10)))
                .as("the store's own key order is the answer, so no sort is needed to get the newest first")
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    void the_cursor_walks_every_row_exactly_once_in_order() throws IOException {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            String id = String.format("e%02d", index);
            record(id, String.format("2026-01-%02dT00:00:00Z", index + 1));
            expected.add(id);
        }
        Collections.reverse(expected);                     // recorded oldest-first, so newest-first is the reverse

        List<String> walked = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 20; page++) {
            RecentIndex.Page window = index().page(after, 5);
            walked.addAll(ids(window));
            if (window.next() == null) {
                break;
            }
            after = window.next();
        }

        assertThat(walked).as("every row once, newest first, with no gap or repeat across page boundaries")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void two_entries_in_the_same_millisecond_both_survive() throws IOException {
        // An instant is not unique, so the key carries a digest of the caller's identity as well. Without it the
        // second write would land on the first one's key and one entry would silently vanish from the index.
        record("first", "2026-05-05T00:00:00Z");
        record("second", "2026-05-05T00:00:00Z");

        assertThat(ids(index().page(null, 10)))
                .as("both rows are kept, and their order among themselves is stable")
                .containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void a_pre_epoch_instant_is_clamped_rather_than_sorting_as_the_newest_row() throws IOException {
        // Inverting a negative epoch-milli overflows past Long.MAX_VALUE into a negative value, which renders with a
        // minus sign and sorts before every digit - so the oldest row imaginable would read as the newest. Clamping
        // is what makes that unrepresentable rather than merely unlikely.
        record("modern", "2026-05-05T00:00:00Z");
        index().record(Instant.parse("1900-01-01T00:00:00Z"), "ancient",
                "ancient".getBytes(StandardCharsets.UTF_8));

        assertThat(ids(index().page(null, 10)).getFirst())
                .as("the modern row is still the newest; the pre-epoch one did not jump the queue")
                .isEqualTo("modern");
    }

    @Test
    void forget_drops_a_row_and_record_is_create_only() throws IOException {
        record("kept", "2026-05-05T00:00:00Z");
        record("dropped", "2026-06-06T00:00:00Z");

        index().forget(Instant.parse("2026-06-06T00:00:00Z"), "dropped");
        // Re-recording an id that is still indexed must not rewrite it: the write is create-only, which is what
        // makes recording twice safe on a path that may retry.
        index().record(Instant.parse("2026-05-05T00:00:00Z"), "kept", "rewritten".getBytes(StandardCharsets.UTF_8));

        assertThat(ids(index().page(null, 10)))
                .as("the forgotten row is gone and the kept one holds its original bytes")
                .containsExactly("kept");
    }
}
