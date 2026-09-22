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
