package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.store.ReadOnlyException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read-only decorator refuses every write ({@code write}, the content-addressed {@code writeBlob}, {@code delete}
 * and the compare-and-set {@code writeVersioned}) with {@link ReadOnlyException} and stores no bytes, while every read
 * passes straight through to the delegate; a scoped view stays read-only too, so a per-tenant / per-repository write is
 * refused just like a root one.
 */
class ReadOnlyArtifactStoreTest {

    @TempDir
    Path root;

    private ArtifactStore delegate() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(int length) {
        return new ByteArrayInputStream(new byte[length]);
    }

    @Test
    void every_write_is_refused_and_stores_nothing() throws IOException {
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(delegate());
        assertThatThrownBy(() -> store.write("blobs/aaa", bytes(10))).isInstanceOf(ReadOnlyException.class);
        assertThatThrownBy(() -> store.writeBlob(bytes(10))).isInstanceOf(ReadOnlyException.class);
        assertThatThrownBy(() -> store.delete("blobs/aaa")).isInstanceOf(ReadOnlyException.class);
        assertThatThrownBy(() -> store.writeVersioned("meta", new byte[] {1}, null))
                .isInstanceOf(ReadOnlyException.class);
        assertThat(store.exists("blobs/aaa")).as("a refused write leaves no bytes").isFalse();
    }

    @Test
    void reads_pass_through_to_the_delegate() throws IOException {
        ArtifactStore raw = delegate();
        raw.write("blobs/aaa", bytes(300));
        raw.writeVersioned("meta", new byte[] {1, 2, 3}, null);
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(raw);

        assertThat(store.exists("blobs/aaa")).isTrue();
        assertThat(store.size("blobs/aaa")).isEqualTo(300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/aaa", out);
        assertThat(out.size()).isEqualTo(300);
        try (var in = store.open("blobs/aaa")) {
            assertThat(in.readAllBytes()).hasSize(300);
        }
        assertThat(store.list("blobs")).contains("aaa");
        assertThat(store.readVersioned("meta")).isPresent();
    }

    @Test
    void a_scoped_view_stays_read_only() {
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(delegate());
        ArtifactStore scoped = store.scope("acme").scope("releases");
        assertThat(scoped).isInstanceOf(ReadOnlyArtifactStore.class);
        assertThatThrownBy(() -> scoped.write("blobs/bbb", bytes(10))).isInstanceOf(ReadOnlyException.class);
    }

    @Test
    void page_delegates_the_native_bounded_pagination_and_never_materialises_a_whole_list() {
        // Without the explicit page() override the read-only wrapper inherits the SPI default page() (list() + sort),
        // throwing away the backend's native bounded pagination and materialising the whole namespace into heap - which
        // OOMs the GC / rollup / quota passes that walk a read-only deployment through this decorator. The override
        // must pass page() straight to the delegate; a read never mutates, so it is safe on a read-only store.
        AtomicBoolean listed = new AtomicBoolean(false);
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(new PagingProbe(listed));

        List<String> seen = new ArrayList<>();
        store.page("blobs", "", 100, seen::add);

        assertThat(seen).as("the backend's native bounded paging flows through the read-only decorator")
                .containsExactly("blobs/a", "blobs/b");
        assertThat(listed.get())
                .as("page() must not inherit the SPI default (list() + sort) and materialise the whole namespace")
                .isFalse();
    }

    /** A backend that answers {@code page()} natively and flags any whole-namespace {@code list()} - so a wrapper that
     *  inherited the SPI default {@code page()} (which lists then sorts) is caught reintroducing the materialisation.
     *  Every other operation is unused by the test. */
    private static final class PagingProbe implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final AtomicBoolean listed;

        private PagingProbe(AtomicBoolean listed) {
            this.listed = listed;
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            consumer.accept("blobs/a");
            consumer.accept("blobs/b");
        }

        @Override
        public List<String> list(String prefix) {
            listed.set(true);
            return List.of();
        }

        @Override public ArtifactStore scope(String tenant) { return this; }
        @Override public boolean exists(String key) { throw new UnsupportedOperationException(); }
        @Override public void read(String key, OutputStream out) { throw new UnsupportedOperationException(); }
        @Override public InputStream open(String key) { throw new UnsupportedOperationException(); }
        @Override public void write(String key, InputStream in) { throw new UnsupportedOperationException(); }
        @Override public String writeBlob(InputStream in) { throw new UnsupportedOperationException(); }
        @Override public long size(String key) { throw new UnsupportedOperationException(); }
        @Override public void delete(String key) { throw new UnsupportedOperationException(); }
        @Override public Optional<Versioned> readVersioned(String key) { throw new UnsupportedOperationException(); }
        @Override public boolean writeVersioned(String key, byte[] content, Object expected) {
            throw new UnsupportedOperationException();
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
