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
 *
 * <p><b>The temporary directory is shared with every other test JVM of the lane, and nothing in it carries the
 * identity of the render that made it.</b> Snapshotting the directory first excludes what existed BEFORE and cannot
 * exclude what a peer creates while this test runs, so a candidate here may be somebody else's spool - which is fine
 * for the mode (every spool is created through the same helper) and is what both loops below are written around. A
 * peer's spool can vanish mid-read and can outlive this render; this one's can do neither, because its generator is
 * blocked holding it. Read once as a plain set, this test fails about as often as a peer renders beside it - measured
 * 2026-09-16 on a full lane, a {@code NoSuchFileException} on a file another JVM had already finished with.
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
        Set<Path> observed = new HashSet<>();   // the spools this render was seen writing, judged on their own below
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
            int read = 0;
            for (Path spool : spools) {
                Set<PosixFilePermission> mode;
                try {
                    mode = Files.getPosixFilePermissions(spool);
                } catch (NoSuchFileException peers) {
                    // Provably not this render's: its spool is pinned open by a generator blocked on `release`, so it
                    // cannot vanish while the mode is read. One that does belongs to a peer JVM that has finished
                    // with it, and it is already removed, which is all the loop after the join would have asked.
                    continue;
                }
                observed.add(spool);
                read++;
                assertThat(mode)
                        .as("%s is owner-only for the life of the render", spool.getFileName())
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
            // The vacuity guard, and it is this render's own: the one spool that cannot have gone is ours.
            assertThat(read).as("the render spooled into the temporary directory").isPositive();
        } finally {
            release.countDown();
            render.join(15_000);
        }
        assertThat(failure.get()).as("the render completed").isNull();
        // Awaited rather than read once, for the same reason the mode loop catches: a peer's spool may still be being
        // written when this render ends. Every spool of every render is transient, so a deadline that outlasts the
        // slowest render in the lane keeps the claim exact for this one - a spool this render LEAKED never goes, at
        // any deadline. Bounded once over the whole set rather than per file, so a real leak costs the wait once.
        assertThat(awaitRemoval(observed, Duration.ofSeconds(30)))
                .as("every spool seen during the render was removed with the render that made it")
                .isEmpty();
    }

    /** The spools still on disk when they have all gone or the deadline passes, whichever comes first. */
    private static Set<Path> awaitRemoval(Set<Path> spools, Duration deadline) throws InterruptedException {
        Set<Path> lingering = new HashSet<>(spools);
        long until = System.nanoTime() + deadline.toNanos();
        while (true) {
            lingering.removeIf(spool -> !Files.exists(spool));
            if (lingering.isEmpty() || System.nanoTime() >= until) {
                return lingering;
            }
            Thread.sleep(50);
        }
    }

    private static Set<Path> spools(Path tmp) throws IOException {
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("jenreg-listing"))
                    .collect(Collectors.toSet());
        }
    }
}
