package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.BrowseController;
import build.jenesis.repository.ui.BrowseRow;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The console browse controller's listing: it pages the immediate children of a browse path (never materialising a
 * possibly-millions-entry directory as one {@code List}), classifies folder-vs-artifact with a bounded one-element
 * probe (never a full subtree {@code list()} per child), and caps what one browse renders while flagging truncation.
 */
class BrowseControllerTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_browse_pages_children_and_probes_folders_without_a_full_directory_listing() throws IOException {
        // One artifact directly under com/example and one under a nested subfolder, so the browse must classify a
        // folder (nested/) vs an artifact (a-1.0.jar). The folder probe must be a bounded seek, not a full list().
        publication.link("/com/example/a-1.0.jar", store.writeBlob(
                new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8))));
        publication.link("/com/example/nested/b-1.0.jar", store.writeBlob(
                new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));

        CountingList counting = new CountingList(store);
        BrowseController controller = new BrowseController(counting);
        Model model = new ConcurrentModel();
        controller.browse("com/example", model);

        List<BrowseRow> entries = (List<BrowseRow>) model.getAttribute("entries");
        assertThat(entries).extracting(BrowseRow::name).containsExactlyInAnyOrder("a-1.0.jar", "nested");
        assertThat(entries).filteredOn(e -> e.name().equals("nested")).singleElement()
                .satisfies(e -> assertThat(e.folder()).isTrue());
        assertThat(entries).filteredOn(e -> e.name().equals("a-1.0.jar")).singleElement()
                .satisfies(e -> assertThat(e.folder()).isFalse());
        assertThat(model.getAttribute("truncated")).isEqualTo(false);
        assertThat(counting.lists())
                .as("children are paged and folders probed by a bounded page - never a full list()").isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_leaf_a_get_would_not_serve_is_screened_out_of_the_browse() throws IOException {
        // The browse must disclose only what a GET would serve: a leaf whose blob is absent (a withheld/retracted
        // artifact, or a pointer whose blob a garbage collection reclaimed) resolves located() to empty and 404s on a
        // GET, so its name and tree position must NOT appear in the browse - the same screen the raw listing applies.
        // A live sibling stays listed; a sub-directory is kept unconditionally.
        publication.link("/com/example/live-1.0.jar", store.writeBlob(
                new ByteArrayInputStream("live".getBytes(StandardCharsets.UTF_8))));
        publication.link("/com/example/gone-1.0.jar",
                "0000000000000000000000000000000000000000000000000000000000000000");   // pointer to a blob that is gone
        publication.link("/com/example/nested/b-1.0.jar", store.writeBlob(
                new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));

        BrowseController controller = new BrowseController(store);
        Model model = new ConcurrentModel();
        controller.browse("com/example", model);

        List<BrowseRow> entries = (List<BrowseRow>) model.getAttribute("entries");
        assertThat(entries).extracting(BrowseRow::name)
                .as("the dangling leaf is screened out; the live leaf and the sub-directory stay")
                .containsExactlyInAnyOrder("live-1.0.jar", "nested");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_directory_larger_than_the_render_cap_is_capped_and_flagged_truncated() throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 1001; i++) {   // one past the render cap
            publication.link(String.format("/big/v%04d.jar", i), hash);
        }
        BrowseController controller = new BrowseController(store);
        Model model = new ConcurrentModel();
        controller.browse("big", model);

        List<BrowseRow> entries = (List<BrowseRow>) model.getAttribute("entries");
        assertThat(entries).as("the render is capped so a huge directory cannot OOM the console").hasSize(1000);
        assertThat(model.getAttribute("truncated")).as("and the cap is surfaced, not silent").isEqualTo(true);
    }

    @Test
    void the_export_emits_one_slice_per_request_and_a_cursor_to_continue_from() throws IOException {
        // The export is a sequence of bounded requests, never one walk of the whole repository: a slice carries at
        // most `limit` entries and, when more remain, a last line with the cursor the next request resumes past.
        String hash = store.writeBlob(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 5; i++) {
            publication.link(String.format("/export/v%d.jar", i), hash);
        }
        BrowseController controller = new BrowseController(store);

        List<String> first = lines(controller, null, "2");
        assertThat(first).hasSize(3);
        assertThat(first.subList(0, 2)).allMatch(line -> line.startsWith("{\"path\":\"/export/v"));
        assertThat(first.get(2)).startsWith("{\"cursor\":\"export/v1.jar\"");

        List<String> second = lines(controller, "export/v1.jar", "2");
        assertThat(second).hasSize(3);
        assertThat(second.get(0)).contains("/export/v2.jar");
        List<String> last = lines(controller, "export/v3.jar", "2");
        assertThat(last).as("the last slice carries no cursor").hasSize(1);
        assertThat(last.get(0)).contains("/export/v4.jar");
        assertThat(lines(controller, null, "1000000")).as("a limit past the slice cap is clamped, not honoured")
                .hasSize(5);
    }

    private static List<String> lines(BrowseController controller, String cursor, String limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        jakarta.servlet.http.HttpServletResponse response = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(jakarta.servlet.WriteListener listener) {
            }

            @Override
            public void write(int b) {
                bytes.write(b);
            }
        });
        controller.assets(cursor, limit, response);
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    /** A store decorator that counts {@code list(prefix)} calls and delegates {@code page(...)} to the real backend's
     *  efficient seek (never the default {@code page} that re-lists), so a test can prove the browse never full-lists. */
    private static final class CountingList implements ArtifactStore {

        private final ArtifactStore delegate;
        private int lists;

        private CountingList(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        private int lists() {
            return lists;
        }

        @Override
        public List<String> list(String prefix) {
            lists++;
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
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
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
