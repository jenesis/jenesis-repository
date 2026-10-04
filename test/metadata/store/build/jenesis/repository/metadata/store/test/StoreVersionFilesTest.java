package build.jenesis.repository.metadata.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.VersionFiles;
import build.jenesis.repository.metadata.store.StoreVersionFiles;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ForwardingArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * A version's files are listed in its own document: recorded keys are unioned, a key listed already writes nothing,
 * a version never recorded answers empty rather than an empty list, and reading never writes.
 */
class StoreVersionFilesTest {

    private static final String ECO = "PyPI";
    private static final String COORD = "widget";
    private static final String VERSION = "1.0";

    @TempDir
    Path root;

    private CountingStore store;

    @BeforeEach
    void setUp() {
        store = new CountingStore(ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null));
    }

    @Test
    void the_installed_implementation_is_the_version_document() {
        assertThat(VersionFiles.installed()).isInstanceOf(StoreVersionFiles.class);
    }

    @Test
    void recorded_keys_are_listed_in_key_order() throws IOException {
        VersionFiles files = new StoreVersionFiles();
        files.record(store, ECO, COORD, VERSION, "pypi/widget/widget-1.0.tar.gz");
        files.record(store, ECO, COORD, VERSION, "pypi/widget/widget-1.0-py3-none-any.whl");

        assertThat(files.listed(store, ECO, COORD, VERSION)).get().asInstanceOf(
                list(String.class)).containsExactly(
                "pypi/widget/widget-1.0-py3-none-any.whl", "pypi/widget/widget-1.0.tar.gz");
        assertThat(files.listed(store, ECO, COORD, "2.0")).as("a version whose files were never recorded").isEmpty();
    }

    @Test
    void a_key_listed_already_writes_nothing_and_reading_never_writes() throws IOException {
        VersionFiles files = new StoreVersionFiles();
        files.record(store, ECO, COORD, VERSION, "pypi/widget/widget-1.0.tar.gz");
        int writes = store.writes.get();

        files.record(store, ECO, COORD, VERSION, "pypi/widget/widget-1.0.tar.gz");
        files.listed(store, ECO, COORD, VERSION);
        files.listed(store, ECO, COORD, "2.0");

        assertThat(store.writes.get()).as("a republish of a listed file, and every read").isEqualTo(writes);
    }

    @Test
    void concurrent_records_of_one_version_all_land() throws Exception {
        VersionFiles files = new StoreVersionFiles();
        int writers = 8;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            for (int index = 0; index < writers; index++) {
                String key = "pypi/widget/file-" + index;
                pool.submit(() -> {
                    barrier.await();
                    files.record(store, ECO, COORD, VERSION, key);
                    return null;
                });
            }
        }

        assertThat(files.listed(store, ECO, COORD, VERSION)).get().asInstanceOf(
                list(String.class)).hasSize(writers);
    }

    /** Counts every write the store is asked for. */
    private static final class CountingStore extends ForwardingArtifactStore {

        final AtomicInteger writes = new AtomicInteger();

        CountingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            writes.incrementAndGet();
            super.write(key, in);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            writes.incrementAndGet();
            return super.writeVersioned(key, content, expected);
        }

        @Override
        public boolean writeVersioned(String key, InputStream content, long length, Object expected)
                throws IOException {
            writes.incrementAndGet();
            return super.writeVersioned(key, content, length, expected);
        }
    }
}
