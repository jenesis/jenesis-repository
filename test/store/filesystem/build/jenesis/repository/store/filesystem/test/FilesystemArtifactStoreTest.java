package build.jenesis.repository.store.filesystem.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is <em>particular</em> to the filesystem store, on a {@code @TempDir}. The cross-backend {@link ArtifactStore}
 * contract - keyed and content-addressed round-trips, sizing, absence, scoping and its traversal screen, ordered
 * paging, compare-and-set create/update, opaque version tokens, per-entry batch outcomes - moved into the shared
 * {@code StoreContract} kit, which runs all of it against this backend in {@code test/store/contract}; re-asserting it
 * here is what let the four backend suites drift apart in the first place.
 *
 * <p>What stays is what only a filesystem can express or only this backend implements: deletion tidies the empty
 * container directories it leaves behind, listing hides an atomic write's in-flight {@code .upload*.tmp} sibling, the
 * last-modified token advances strictly even for updates inside one clock tick, concurrent compare-and-set increments
 * never lose one another, an aborted write leaks no spool file, a blob and its containers are created owner-only
 * rather than at the process umask, {@code readVersioned} reads a racing delete as empty instead of throwing, and a
 * key that escapes the store root is rejected on the <em>read</em> path too (the object stores have no such path to
 * escape, so this guard has no cross-backend counterpart).
 */
class FilesystemArtifactStoreTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void delete_removes_the_blob_and_tidies_the_empty_containers_it_leaves() throws IOException {
        store.write("a/b/c.bin", bytes("x"));
        store.delete("a/b/c.bin");
        assertThat(store.exists("a/b/c.bin")).isFalse();
        assertThat(store.list("a")).as("the now-empty parent directories are gone").isEmpty();
    }

    @Test
    void list_returns_sorted_immediate_children_and_hides_an_atomic_write_temp_file() throws IOException {
        store.write("d/one", bytes("1"));
        store.write("d/two", bytes("2"));
        Files.createFile(root.resolve("d").resolve(".upload12345.tmp"));

        assertThat(store.list("d")).containsExactly("one", "two");
        assertThat(store.list("missing")).isEmpty();
    }

    @Test
    void a_ranged_read_seeks_and_streams_only_the_window() throws IOException {
        store.write("r/blob", bytes("0123456789"));
        ByteArrayOutputStream window = new ByteArrayOutputStream();
        class Sink extends java.io.OutputStream implements ArtifactStore.RangedSink {
            @Override
            public long offset() {
                return 2;
            }

            @Override
            public long length() {
                return 3;
            }

            @Override
            public java.io.OutputStream sink() {
                return window;
            }

            @Override
            public void write(int b) {
                window.write(b);
            }
        }
        store.read("r/blob", new Sink());
        assertThat(window.toString(StandardCharsets.UTF_8)).isEqualTo("234");
    }

    @Test
    void write_versioned_tokens_strictly_advance_so_a_stale_token_never_passes() throws IOException {
        assertThat(store.writeVersioned("m/x", "0".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object token = store.readVersioned("m/x").orElseThrow().token();
        for (int update = 1; update <= 100; update++) {
            assertThat(store.writeVersioned("m/x", Integer.toString(update).getBytes(StandardCharsets.UTF_8), token))
                    .isTrue();
            Object next = store.readVersioned("m/x").orElseThrow().token();
            assertThat((long) next)
                    .as("the token advances on every update, even for updates inside one clock tick")
                    .isGreaterThan((long) token);
            token = next;
        }
    }

    @Test
    void concurrent_compare_and_set_updates_never_lose_one_another() throws Exception {
        assertThat(store.writeVersioned("m/counter", "0".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        int writers = 4, increments = 25;
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(writers)) {
            for (int writer = 0; writer < writers; writer++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        while (true) {
                            ArtifactStore.Versioned versioned = store.readVersioned("m/counter").orElseThrow();
                            int current = Integer.parseInt(new String(versioned.content(), StandardCharsets.UTF_8));
                            if (store.writeVersioned("m/counter",
                                    Integer.toString(current + 1).getBytes(StandardCharsets.UTF_8),
                                    versioned.token())) {
                                break;
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(new String(store.readVersioned("m/counter").orElseThrow().content(), StandardCharsets.UTF_8))
                .as("every compare-and-set increment landed; none was silently lost")
                .isEqualTo(Integer.toString(writers * increments));
    }

    @Test
    void an_aborted_write_leaves_no_partial_key_and_no_temp_file() throws IOException {
        InputStream aborting = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ < 3) {
                    return 'x';
                }
                throw new IOException("client hung up");
            }
        };

        assertThatThrownBy(() -> store.write("d/aborted", aborting)).isInstanceOf(IOException.class);

        assertThat(store.exists("d/aborted")).as("nothing lands at the key").isFalse();
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile))
                    .as("the atomic write's spool file is cleaned up, not leaked").isEmpty();
        }
    }

    @Test
    void read_versioned_reads_a_racing_delete_as_empty_never_throwing() throws Exception {
        // The isRegularFile probe and the token/content reads are not one atomic operation: a concurrent delete can
        // vanish the file in the window. The contract - and the object-store backends' 404 -> empty - is
        // Optional.empty(), never an escaping NoSuchFileException. A writer flips the key in and out while a reader
        // hammers readVersioned; the reader must only ever see the written content or absence, and never throw.
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                while (!stop.get()) {
                    store.writeVersioned("race/key", "v".getBytes(StandardCharsets.UTF_8), null);
                    store.delete("race/key");
                }
            } catch (Throwable t) {
                writerFailure.compareAndSet(null, t);
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 20_000 && writerFailure.get() == null; i++) {
                // A pre-fix readVersioned would let a NoSuchFileException escape here on the race; it must not.
                Optional<ArtifactStore.Versioned> read = store.readVersioned("race/key");
                if (read.isPresent()) {
                    assertThat(new String(read.get().content(), StandardCharsets.UTF_8))
                            .as("a present read is always the whole written content, never a torn half").isEqualTo("v");
                }
            }
        } finally {
            stop.set(true);
            writer.join(30_000);
        }
        assertThat(writerFailure.get()).as("the churn writer never faulted").isNull();
    }

    @Test
    void a_written_file_and_the_dirs_it_creates_are_owner_only_not_umask_world_readable() throws IOException {
        // The whole point of the hardening: a blob and every container the store creates for it are owner-only
        // (rw-------/rwx------) rather than inheriting the process umask's world-readable 022 default. POSIX
        // permissions only model this on a POSIX filesystem, so skip the assertion where the FS cannot express it.
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only permission tightening is only observable on a POSIX filesystem");

        store.write("a/b/c.bin", bytes("secret"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("a/b/c.bin"))))
                .as("a written blob is created rw-------, never the umask's world-readable default").isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("a"))))
                .as("a container directory the store creates is rwx------").isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("a/b"))))
                .as("a nested container directory the store creates is rwx------").isEqualTo("rwx------");
    }

    @Test
    void a_content_addressed_blob_is_created_owner_only() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only permission tightening is only observable on a POSIX filesystem");

        String hash = store.writeBlob(bytes("payload"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("blobs").resolve(hash))))
                .as("a content-addressed blob is created rw-------").isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("blobs"))))
                .as("the blobs container directory is rwx------").isEqualTo("rwx------");
    }

    @Test
    void a_key_that_escapes_the_store_root_is_rejected() {
        assertThatThrownBy(() -> store.exists("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
