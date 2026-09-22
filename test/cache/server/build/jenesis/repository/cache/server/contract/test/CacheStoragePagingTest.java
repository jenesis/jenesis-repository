package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.walk.Traversal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The cache-storage enumerations are bounded, on a store seeded <em>past</em> the bound.
 *
 * <p>The shared {@code CacheStorageContract} states this property once and runs it against all four backends, but at a
 * page width of two over a handful of objects - it has to, because three of those backends reach a container emulator
 * and seeding thousands of objects into one would make the contract kit unusable. This test is the other half: a real
 * store with more entries in one project than the SPI's own default page width, so what is asserted here is not "the
 * limit argument is honoured" but "a namespace larger than the bound is delivered in bounded pages and nothing is
 * lost between them".
 *
 * <p>It is the test the defect fails. Before this change {@code entries(project)} returned a {@code List} holding one
 * record per cached blob and there was no bound to pass and no outcome to read, so a caller could not have written
 * the first assertion here at all - and, crucially, could not have discovered by any means that what it held was a
 * prefix. The size-cap sweep then sorted that list, which is the allocation this bound removes: it ran on the console
 * request thread, over a namespace a build inflates by caching, on a node that had already run low on disk.
 *
 * <p>The negative control was performed during implementation and is what makes the claim above checkable rather than
 * asserted: making {@code FilesystemStorage.entries} ignore its limit and answer {@code exhausted} - which is exactly
 * the pre-change behaviour re-expressed in the new signature - fails
 * {@link #a_project_larger_than_the_page_is_delivered_in_bounded_pages} on the delivered count ("the unbounded
 * enumeration returned all 1250 in one list") and {@link #the_cursor_drains_the_whole_project_exactly_once} on the
 * round count, and restoring the bound makes both green again.
 */
public class CacheStoragePagingTest {

    @TempDir
    Path base;

    /** Comfortably past {@link CacheStorage#PAGE}, so a page boundary falls inside the project rather than at its
     *  end, and small enough that seeding it is a fraction of a second. */
    private static final int SEEDED = CacheStorage.PAGE + 250;

    @Test
    void a_project_larger_than_the_page_is_delivered_in_bounded_pages() throws IOException {
        CacheStorage storage = seeded();

        List<CacheStorage.Stored> first = new ArrayList<>();
        Traversal.Result page = storage.entries("wide", null, CacheStorage.PAGE, first::add);

        assertThat(first).as("one call delivers at most the bound it was given, however much the project holds - "
                        + "the unbounded enumeration returned all %d in one list", SEEDED)
                .hasSize(CacheStorage.PAGE);
        assertThat(page.truncated()).as("and says so: a caller learns it holds a page rather than a listing, which "
                + "is the half of the defect no amount of heap would have fixed").isTrue();
        assertThat(page.cursor()).as("a truncation always carries the cursor that resumes it").isPresent();
        assertThat(page.cursor().orElseThrow()).as("the cursor is a store key naming the project it belongs to")
                .startsWith("wide/");
        assertThat(page.delivered()).isEqualTo(CacheStorage.PAGE);
    }

    @Test
    void the_cursor_drains_the_whole_project_exactly_once() throws IOException {
        CacheStorage storage = seeded();

        List<String> drained = new ArrayList<>();
        String cursor = null;
        int rounds = 0;
        while (true) {
            Traversal.Result page = storage.entries("wide", cursor, 100, stored -> drained.add(name(stored)));
            rounds++;
            if (page.exhausted()) {
                break;
            }
            cursor = page.cursor().orElseThrow();
            assertThat(rounds).as("a cursor that makes no forward progress is a livelock dressed up as paging")
                    .isLessThan(SEEDED);
        }

        assertThat(drained).as("every entry is delivered - one dropped between two pages is a blob no size cap ever "
                        + "counts and no reclaim ever frees").hasSize(SEEDED);
        assertThat(new LinkedHashSet<>(drained)).as("and each of them exactly once, never re-delivering a boundary")
                .hasSize(SEEDED);
        assertThat(rounds).as("the drain really did page rather than answering in one round")
                .isGreaterThan(SEEDED / 100);
    }

    @Test
    void the_page_holds_its_order_across_a_resume() throws IOException {
        CacheStorage storage = seeded();

        List<String> paged = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result page = storage.entries("wide", cursor, 64, stored -> paged.add(name(stored)));
            if (page.exhausted()) {
                break;
            }
            cursor = page.cursor().orElseThrow();
        }
        List<String> whole = new ArrayList<>();
        storage.entries("wide", null, SEEDED, stored -> whole.add(name(stored)));

        assertThat(paged).as("the order a resumed enumeration delivers in is the order a single call delivers in - "
                + "without one total order there is nothing for a cursor to resume against").isEqualTo(whole);
    }

    @Test
    void a_non_positive_bound_is_refused_rather_than_answered_with_an_empty_page() throws IOException {
        CacheStorage storage = seeded();
        // An empty page is indistinguishable from a drained project, so answering one would let a caller's
        // off-by-one read as "this project is empty" - and, in a reclaim, as "there is nothing here to free".
        for (int limit : new int[]{0, -1}) {
            assertThatIllegalArgumentException().as("entries with the bound %d", limit)
                    .isThrownBy(() -> storage.entries("wide", null, limit, _ -> {
                    }));
            assertThatIllegalArgumentException().as("projects with the bound %d", limit)
                    .isThrownBy(() -> storage.projects(null, limit, _ -> {
                    }));
            assertThatIllegalArgumentException().as("listDir with the bound %d", limit)
                    .isThrownBy(() -> storage.listDir("", null, limit, _ -> {
                    }));
        }
    }

    @Test
    void a_cursor_from_another_project_is_refused_rather_than_silently_resumed() throws IOException {
        CacheStorage storage = seeded();
        // Resuming one project's sweep from another's cursor would skip whatever sorts below it and then report the
        // project swept, which is a silent under-eviction rather than a failure.
        assertThatIllegalArgumentException().isThrownBy(() -> storage.entries("wide", "other/aa/01", 10, _ -> {
        }));
        assertThatIllegalArgumentException().isThrownBy(() -> storage.listDir(".users", "elsewhere/x", 10, _ -> {
        }));
    }

    /** A tenant scope holding one project with more entries than the SPI's page width, plus a sibling project and the
     *  project's own policy document - neither of which may leak into the paged answer. */
    private CacheStorage seeded() throws IOException {
        CacheStorage storage = CacheStorages.filesystem(Files.createDirectories(base.resolve("cache"))).scope("acme");
        for (int index = 0; index < SEEDED; index++) {
            storage.store(new CacheStorage.Entry("wide", step(index), inputs(index)),
                    new ByteArrayInputStream(new byte[]{(byte) index}));
        }
        storage.store(new CacheStorage.Entry("narrow", "ff", "ff"), new ByteArrayInputStream(new byte[]{1}));
        Properties policy = new Properties();
        policy.setProperty("size", "100");
        storage.writeConfig("wide", "cache.properties", policy);
        return storage;
    }

    /** Spread the entries over 16 step containers, so the project is a tree rather than one flat directory and a page
     *  boundary can fall in the middle of one of them. */
    private static String step(int index) {
        return HexFormat.of().toHexDigits((byte) (index % 16));
    }

    private static String inputs(int index) {
        return HexFormat.of().toHexDigits(index);
    }

    /** The entry's {@code <step>/<inputs>} from its token. The token is documented opaque, so this reads its text
     *  and takes the trailing two segments rather than casting it to whatever the backend happens to use. */
    private static String name(CacheStorage.Stored stored) {
        String[] segments = String.valueOf(stored.token()).split("[/\\\\]");
        return segments[segments.length - 2] + "/" + segments[segments.length - 1];
    }
}
