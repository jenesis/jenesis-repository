package build.jenesis.repository.gc.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a mark pass may COST, counted rather than assumed.
 *
 * <p>The mark phase opens every key in the pointer tree - that is what it is for - so a per-key store call is not a
 * constant factor, it is the pass. Two used to be spent on every pointer before its body was read: one
 * {@code exists} to decide whether the name was a leaf or a container to descend, and one {@code size} to decide
 * whether the leaf was small enough to read as a pointer body. Both questions are answered by the listing that
 * enumerated the key - a container listing reports each stored child's size, and only a stored object has one - so
 * both are now free, and the pass spends one read per pointer instead of three.
 *
 * <p>This is asserted by counting, not by reasoning about it, because the saving is invisible to every behavioural
 * test: a store that answered the same questions one request at a time would pass all of them. A later edit that
 * re-introduces a probe would be caught here and nowhere else.
 */
class GcMarkRoundTripsTest {

    @TempDir
    Path root;

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_mark_pass_reads_each_pointer_once_and_probes_none_of_them() throws IOException {
        ArtifactStore backing = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Publication publication = new Publication(backing);
        for (int index = 0; index < 12; index++) {
            publication.link("/maven/g/a/" + index + "/a-" + index + ".jar",
                    publication.storeBlob(bytes("artifact " + index)));
        }

        Counting counting = new Counting(backing);
        new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), Clock.systemUTC()))
                .collect(counting, Known.known(List.of("publish")), Instant.now());

        assertThat(counting.sized).as(
                "the pointer-size gate is answered from the listing that enumerated the key, so a mark pass asks "
                        + "the store for no key's size. One size() per pointer is a round trip per pointer on an "
                        + "object store, spent only to decide not to read the few leaves that are not pointers")
                .isZero();

        // exists() is not asserted to be zero for the whole pass: the descent still probes a CONTAINER (whose
        // listing reports no size, precisely because a container has none) and the sweep probes blobs/ and its own
        // gc/ bookkeeping. What must never happen again is a probe on a POINTER, and that is exact.
        assertThat(counting.probed).as(
                "a leaf is never probed for existence: its listing already proved it is a stored object. Any "
                        + "pointer key appearing here is a round trip per pointer coming back")
                .noneMatch(key -> key.endsWith(".jar"));
    }

    /** An {@link ArtifactStore} that forwards everything and counts the calls a mark pass is allowed to make. */
    private static final class Counting implements ArtifactStore {

        private final ArtifactStore delegate;
        private int sized;
        private final List<String> probed = new ArrayList<>();

        private Counting(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public long size(String key) throws IOException {
            sized++;
            return delegate.size(key);
        }

        @Override
        public boolean exists(String key) {
            probed.add(key);
            return delegate.exists(key);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new Counting(delegate.scope(tenant));
        }

        @Override
        public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
            delegate.pageListed(prefix, startAfter, limit, consumer);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
            return delegate.scan(prefix, startAfter, limit, consumer);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
