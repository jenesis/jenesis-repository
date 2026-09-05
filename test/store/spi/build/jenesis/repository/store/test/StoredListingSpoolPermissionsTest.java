package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file a stored listing is rendered into on its way to the store is owner-only ({@code rw-------}) for the length of
 * the render. A listing is rendered outside the heap - a generator emits into a temporary file that is digested as it
 * goes and then streamed into the store - and that file sits in the shared temporary directory while the generator
 * runs. The JDK creates a temporary file owner-only on a POSIX filesystem, so this was true before the render went
 * through {@code OwnerOnly} and this test was green against the plain call; it pins the mode against a future spool
 * that opens the file some other way, and says so rather than claiming a red it never had.
 *
 * <p>The spool exists only while the generator runs, so the generator blocks after its first entry until the test has
 * looked at the file's mode. Skips where the filesystem cannot express POSIX modes; no Docker, always runs.
 */
class StoredListingSpoolPermissionsTest {

    private static final StoredListing.Codec LINES = StoredListing.Codec.delimited("\n",
            line -> line.substring(0, line.indexOf(' ')));

    @TempDir
    Path root;

    @Test
    void the_render_spool_is_created_owner_only() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only modes are only observable on a POSIX filesystem");
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null).scope("acme");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = spools(tmp);

        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StoredListing.Generator generator = sink -> {
            sink.accept("a", "a 1\n".getBytes(StandardCharsets.UTF_8));
            inFlight.countDown();                       // the spool is on disk and being written
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.accept("b", "b 2\n".getBytes(StandardCharsets.UTF_8));
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread render = new Thread(() -> {
            try (StoredListing.Served served = StoredListing.open(store,
                    StoredListing.Spec.of("spooled", LINES, generator)).orElseThrow()) {
                served.header();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        render.start();
        try {
            assertThat(inFlight.await(15, TimeUnit.SECONDS)).as("the generator reached its first entry").isTrue();
            Set<Path> spools = new HashSet<>(spools(tmp));
            spools.removeAll(before);
            assertThat(spools).as("the render spooled into the temporary directory").isNotEmpty();
            for (Path spool : spools) {
                assertThat(Files.getPosixFilePermissions(spool))
                        .as("%s is owner-only for the life of the render", spool.getFileName())
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        } finally {
            release.countDown();
            render.join(15_000);
        }
        assertThat(failure.get()).as("the render completed").isNull();
        assertThat(spools(tmp)).as("the spool was removed with the render").isEqualTo(before);
    }

    private static Set<Path> spools(Path tmp) throws IOException {
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("jenreg-listing"))
                    .collect(Collectors.toSet());
        }
    }
}
