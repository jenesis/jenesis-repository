package build.jenesis.repository.ui.admin.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.ui.store.CacheService;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project screens read a stored count, never a sweep of the cache on the request: a project that has never been
 * counted says so; a count runs in the background and stores what it found; an eviction runs the same way and leaves
 * its outcome and the entries it left behind for the screen to read back.
 */
class CacheStatsTest {

    @TempDir
    private Path cacheRoot;

    private CacheStorage storage;
    private CacheService service;

    @BeforeEach
    void setUp() throws IOException {
        storage = CacheStorages.filesystem(cacheRoot);
        storage.createProject("libs");
        service = new CacheService(storage, AuditTrail.none(), () -> "acme", () -> "octo");
    }

    @Test
    void a_project_is_unknown_until_counted_and_a_count_stores_what_it_found() throws Exception {
        storage.store(new CacheStorage.Entry("libs", "aa", "01"),
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        storage.store(new CacheStorage.Entry("libs", "aa", "02"),
                new ByteArrayInputStream("defgh".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.project("libs").stats().known()).as("no pass has run").isFalse();
        assertThat(service.listProjects()).singleElement()
                .satisfies(summary -> assertThat(summary.stats().known()).isFalse());

        assertThat(service.recount("libs")).isTrue();
        CacheService.Stats counted = await(() -> service.stats("libs"));

        assertThat(counted.entryCount()).isEqualTo(2);
        assertThat(counted.totalBytes()).isEqualTo(8);
        assertThat(counted.lastAction()).isEqualTo("count");
        assertThat(service.listProjects()).singleElement()
                .satisfies(summary -> assertThat(summary.entryCount()).isEqualTo(2));
    }

    @Test
    void an_eviction_runs_in_the_background_and_leaves_its_outcome_and_the_count_behind() throws Exception {
        storage.store(new CacheStorage.Entry("libs", "aa", "01"),
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.clearAll("libs")).isTrue();
        CacheService.Stats cleared = await(() -> service.stats("libs"));

        assertThat(cleared.entryCount()).isZero();
        assertThat(cleared.lastAction()).isEqualTo("clear");
        assertThat(cleared.lastOutcome()).startsWith("deleted 1 entries");
    }

    /**
     * A deletion removes the entries, the settings and the stored figures alike, and writes nothing back when it
     * lands - a pass that stored its figures afterwards would bring the project back, since a project exists while
     * anything is under it. It runs on the calling thread here so the assertion is about what it left, not a race.
     */
    @Test
    void a_deleted_project_leaves_nothing_behind_and_is_no_longer_listed() throws Exception {
        storage.store(new CacheStorage.Entry("libs", "aa", "01"),
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        storage.createProject("keep");
        CacheService deleting = new CacheService(storage, AuditTrail.none(), () -> "acme", () -> "octo",
                CacheService.Passes.CALLING_THREAD);

        assertThat(deleting.deleteProject("libs")).isTrue();

        assertThat(storage.projectExists("libs")).as("nothing is left under the project").isFalse();
        assertThat(deleting.listProjects()).extracting(CacheService.ProjectSummary::name).containsExactly("keep");
    }

    /** A deletion shares the sweeps' guard: while one runs it is refused, since the sweep would write its figures
     *  back into the project when it lands. */
    @Test
    void a_deletion_is_refused_while_a_pass_runs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CacheService held = new CacheService(storage, AuditTrail.none(), () -> "acme", () -> "octo",
                (name, pass) -> Thread.ofVirtual().name(name).start(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    pass.run();
                }));
        assertThat(held.recount("libs")).isTrue();
        try {
            assertThat(held.deleteProject("libs")).as("a count is running").isFalse();
            assertThat(storage.projectExists("libs")).isTrue();
        } finally {
            release.countDown();
        }
        await(() -> service.stats("libs"));
    }

    private static CacheService.Stats await(Callable<CacheService.Stats> stats) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            CacheService.Stats current = stats.call();
            if (current.known() && !current.counting()) {
                return current;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the background pass did not land within 5 s");
    }
}
