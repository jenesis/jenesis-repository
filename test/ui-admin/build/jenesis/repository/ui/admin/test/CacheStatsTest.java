package build.jenesis.repository.ui.admin.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.store.CacheService;
import build.jenesis.repository.ui.store.SettingsAdmin;
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

    /** The cache root the service is given, and the tenant's part of it the projects live in. */
    private CacheStorage root;
    private CacheStorage storage;
    private CacheService service;

    @BeforeEach
    void setUp() throws IOException {
        root = CacheStorages.filesystem(cacheRoot);
        storage = root.scope("acme");
        storage.createProject("libs", "gradle", "");
        service = new CacheService(root, AuditTrail.none(), () -> "acme", () -> "octo", settings());
    }

    /** The projects' settings, over the store the cache keeps them in. */
    private SettingsAdmin settings() {
        return new SettingsAdmin(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? cacheRoot.toString() : null));
    }

    @Test
    void a_pass_keeps_the_tenant_its_request_selected_after_the_request_is_gone() throws Exception {
        // The console selects the tenant from the request's session, which a pass running on a thread of its own has
        // none of: the pass must carry the tenant's cache it was started over, not ask for the tenant again.
        AtomicReference<String> selected = new AtomicReference<>("acme");
        List<Runnable> held = new ArrayList<>();
        CacheService console = new CacheService(root, AuditTrail.none(), selected::get, () -> "octo", settings(),
                (name, pass) -> held.add(pass));
        storage.store(new CacheStorage.Entry("libs", "aa", "01"),
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));

        assertThat(console.recount("libs")).isTrue();
        selected.set(null);
        held.forEach(Runnable::run);
        selected.set("acme");

        assertThat(console.stats("libs")).satisfies(stats -> {
            assertThat(stats.counting()).as("the pass finished").isFalse();
            assertThat(stats.entryCount()).isEqualTo(1);
        });
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
    void of_two_nodes_asked_at_once_one_starts_the_pass_and_the_other_finds_it_running() throws Exception {
        // The passes are held rather than run, so the first one stays running for as long as the others ask.
        List<Runnable> held = new CopyOnWriteArrayList<>();
        List<CacheService> nodes = List.of(
                new CacheService(CacheStorages.filesystem(cacheRoot), AuditTrail.none(), () -> "acme", () -> "octo",
                        settings(), (name, pass) -> held.add(pass)),
                new CacheService(CacheStorages.filesystem(cacheRoot), AuditTrail.none(), () -> "acme", () -> "octo",
                        settings(), (name, pass) -> held.add(pass)));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        List<Thread> askers = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            CacheService node = nodes.get(index % 2);
            askers.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (node.recount("libs")) {
                        started.incrementAndGet();
                    }
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            }));
        }
        start.countDown();
        for (Thread asker : askers) {
            asker.join();
        }
        assertThat(started.get()).as("one pass started, whichever node asked first").isEqualTo(1);
        assertThat(held).hasSize(1);
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
        storage.createProject("keep", "gradle", "");
        CacheService deleting = new CacheService(root, AuditTrail.none(), () -> "acme", () -> "octo",
                settings(), CacheService.Passes.CALLING_THREAD);

        assertThat(deleting.deleteProject("libs")).isTrue();

        assertThat(storage.projectExists("libs")).as("nothing is left under the project").isFalse();
        assertThat(deleting.listProjects()).extracting(CacheService.ProjectSummary::name).containsExactly("keep");
    }

    /** A deletion shares the sweeps' guard: while one runs it is refused, since the sweep would write its figures
     *  back into the project when it lands. */
    @Test
    void a_deletion_is_refused_while_a_pass_runs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CacheService held = new CacheService(root, AuditTrail.none(), () -> "acme", () -> "octo",
                settings(), (name, pass) -> Thread.ofVirtual().name(name).start(() -> {
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
