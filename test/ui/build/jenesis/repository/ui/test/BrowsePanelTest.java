package build.jenesis.repository.ui.test;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.BrowsePanel;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The free console's use of the shared mark resolution, over a real store. The browse panel marks each published
 * namespace with the mark of the format that owns it, and all three answers are reachable here from stored data
 * rather than from a fixture: a format that ships a mark, a format that ships none (which is every format this
 * repository bundles today), and a namespace <em>no installed format claims</em> - a repository that still holds
 * what a format module used to serve.
 *
 * <p>That last row is the one the panel could not previously say anything about. It is also the reason
 * {@code FormatMarks} answers "no format claims this" rather than deciding what to draw: the same emptiness means
 * "render nothing" for a bookkeeping bucket and "this is orphaned" for published content, and only the caller knows
 * which it is holding.
 */
class BrowsePanelTest {

    private static final String OCI_MARK =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" stroke=\"currentColor\"><g/></svg>";

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
    void every_published_namespace_is_marked_by_the_format_that_owns_it() throws IOException {
        // Three namespaces holding artifacts, and only two formats installed to serve them.
        publish("/oci/library/app/blobs/sha256-abc");
        publish("/maven/com/example/a-1.0.jar");
        publish("/npm/left-pad/-/left-pad-1.3.0.tgz");

        String body = new BrowsePanel(new FormatMarks(List.of(
                new StubFormat("oci", "/oci/", OCI_MARK),
                new StubFormat("maven", "/maven/", null)))).render(store);

        // The format that ships a mark renders its own document, byte for byte.
        assertThat(body).contains(OCI_MARK);
        // The format that ships none renders its generated figure - a real, stable identity for that format, not a
        // repeat of the neutral box. This is what a page of unbranded formats looks like, and it is why the free
        // console proves the promoted scheme rather than only hosting it.
        assertThat(body).contains(Marks.generated("maven").svg());
        assertThat(body).doesNotContain(Marks.neutral());
        // And the namespace nothing serves is drawn as the orphan it is.
        assertThat(body).contains(Marks.orphaned("npm").svg());
    }

    @Test
    void a_namespace_no_installed_format_claims_says_so_without_relying_on_a_colour() throws IOException {
        // The distinction that must survive a monochrome display and a screen reader: the orphan's mark is already a
        // different drawing (a dashed tile), the row carries the state in its title and aria-label, and it says in
        // words that nothing serves the namespace. The stylesheet's dimming is a third cue on top, never the only
        // one - which is the constraint the downstream console's colour treatment sits on.
        publish("/gem/rack/rack-3.0.0.gem");

        String body = new BrowsePanel(new FormatMarks(List.of())).render(store);

        assertThat(body).contains("stroke-dasharray")
                .contains("title=\"gem (not installed)\"")
                .contains("aria-label=\"gem (not installed)\"")
                .contains("no installed format serves this namespace")
                .contains("app-mark--orphaned");
    }

    @Test
    void an_installed_format_is_never_rendered_as_an_orphan() throws IOException {
        // The two states the resolution must not collapse, checked from the console's side: a format that is present
        // but ships no mark must not pick up any of the orphan's cues, or an operator reads "this plug-in is gone"
        // off a plug-in that is running.
        publish("/maven/com/example/a-1.0.jar");

        String body = new BrowsePanel(new FormatMarks(List.of(new StubFormat("maven", "/maven/", null))))
                .render(store);

        assertThat(body).doesNotContain("stroke-dasharray")
                .doesNotContain("app-mark--orphaned")
                .doesNotContain("not installed")
                .contains("title=\"maven\"");
    }

    @Test
    void the_marks_are_the_same_on_every_render_so_a_console_page_is_stable() throws IOException {
        // A panel renders once per GET and marks are memoized per namespace behind FormatMarks; two renders of
        // unchanged state must produce the same body, which is the panel contract's idempotency clause and the
        // property that lets the page be cached and revalidated at all.
        publish("/maven/com/example/a-1.0.jar");
        publish("/pypi/simple/requests/requests-2.0.tar.gz");

        BrowsePanel panel = new BrowsePanel(new FormatMarks(List.of(new StubFormat("maven", "/maven/", null))));

        assertThat(panel.render(store)).isEqualTo(panel.render(store));
    }

    @Test
    void an_empty_repository_renders_no_marks_at_all() throws IOException {
        // Nothing published means nothing to attribute: the panel keeps its empty state rather than inventing a row
        // with a neutral box on it.
        assertThat(new BrowsePanel(new FormatMarks(List.of())).render(store))
                .contains("The repository is empty")
                .doesNotContain("app-mark");
    }

    private void publish(String path) {
        try {
            publication.link(path, store.writeBlob(
                    new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A format claiming one request prefix, with or without a mark of its own - the two shapes a real format has. */
    private record StubFormat(String name, String prefix, String mark) implements RepositoryFormat, ArtifactLayout {

        @Override
        public boolean handles(String path) {
            return path.startsWith(prefix);
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) {
            throw new UnsupportedOperationException("the browse panel never dispatches a request");
        }

        @Override
        public Optional<IconResource> icon() {
            return Optional.ofNullable(mark).map(IconResource::svg);
        }

        @Override
        public String ecosystem() {
            return name;
        }

        @Override
        public Optional<ArtifactDescriptor> describe(String path) {
            return Optional.empty();
        }

        @Override
        public List<String> paths(String coordinate, String version, ArtifactStore store) {
            return List.of();
        }
    }
}
