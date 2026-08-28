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
import build.jenesis.repository.ui.BrowseCard;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The free console's use of the shared mark resolution, over a real store. The browse card marks each published
 * namespace with the mark of the format that owns it, and all three answers are reachable here from stored data
 * rather than from a fixture: a format that ships a mark, a format that ships none (which is every format this
 * repository bundles today), and a namespace <em>no installed format claims</em> - a repository that still holds
 * what a format module used to serve.
 *
 * <p>That last row is the one the card could not previously say anything about. It is also the reason
 * {@code FormatMarks} answers "no format claims this" rather than deciding what to draw: the same emptiness means
 * "render nothing" for a bookkeeping bucket and "this is orphaned" for published content, and only the caller knows
 * which it is holding.
 *
 * <p>These assert on the value the card prepares, not on a rendered page, and that is the point of the card no
 * longer producing markup: the decision - which mark, whether the format is installed - is what this test is about,
 * and it used to be reachable only by substring-matching the HTML the card had built. How that value reaches the
 * page is the template's business and is proved by the booted console downstream, which renders it for real.
 */
class BrowseCardTest {

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

        List<BrowseCard.Namespace> namespaces = new BrowseCard(new FormatMarks(List.of(
                new StubFormat("oci", "/oci/", OCI_MARK),
                new StubFormat("maven", "/maven/", null)))).model(store).namespaces();

        // The format that ships a mark contributes its own document, byte for byte.
        assertThat(mark(namespaces, "oci")).isEqualTo(OCI_MARK);
        // The format that ships none contributes its generated figure - a real, stable identity for that format, not
        // a repeat of the neutral box. This is what a page of unbranded formats looks like, and it is why the free
        // console proves the promoted scheme rather than only hosting it.
        assertThat(mark(namespaces, "maven")).isEqualTo(Marks.generated("maven").svg());
        assertThat(namespaces).extracting(BrowseCard.Namespace::markSvg).doesNotContain(Marks.neutral());
        // And the namespace nothing serves is drawn as the orphan it is.
        assertThat(mark(namespaces, "npm")).isEqualTo(Marks.orphaned("npm").svg());
    }

    @Test
    void a_namespace_no_installed_format_claims_says_so_without_relying_on_a_colour() throws IOException {
        // The distinction that must survive a monochrome display and a screen reader, decided here: the orphan's
        // mark is a different drawing (a dashed tile) and its title - which the template renders into both the
        // title attribute and the aria-label - names the state in words. The row's "no installed format serves this
        // namespace" note and the dimming the stylesheet adds hang off `installed` being false, and the booted
        // console asserts that they reach the page.
        publish("/gem/rack/rack-3.0.0.gem");

        BrowseCard.Namespace orphan = new BrowseCard(new FormatMarks(List.of())).model(store)
                .namespaces().getFirst();

        assertThat(orphan.name()).isEqualTo("gem");
        assertThat(orphan.installed()).as("nothing serves it, and that is what the row must say").isFalse();
        assertThat(orphan.markSvg()).as("a different drawing, not a differently coloured one")
                .contains("stroke-dasharray");
        assertThat(orphan.markTitle()).isEqualTo("gem (not installed)");
    }

    @Test
    void an_installed_format_is_never_rendered_as_an_orphan() throws IOException {
        // The two states the resolution must not collapse, checked from the console's side: a format that is present
        // but ships no mark must not pick up any of the orphan's cues, or an operator reads "this plug-in is gone"
        // off a plug-in that is running.
        publish("/maven/com/example/a-1.0.jar");

        BrowseCard.Namespace served = new BrowseCard(new FormatMarks(List.of(new StubFormat("maven", "/maven/", null))))
                .model(store).namespaces().getFirst();

        assertThat(served.installed()).isTrue();
        assertThat(served.markSvg()).doesNotContain("stroke-dasharray");
        assertThat(served.markTitle()).isEqualTo("maven").doesNotContain("not installed");
    }

    @Test
    void the_marks_are_the_same_on_every_read_so_a_console_page_is_stable() throws IOException {
        // A card prepares its value once per GET and marks are memoized per namespace behind FormatMarks; two reads
        // of unchanged state must produce the same value, which is the card contract's idempotency clause and the
        // property that lets the page be cached and revalidated at all.
        publish("/maven/com/example/a-1.0.jar");
        publish("/pypi/simple/requests/requests-2.0.tar.gz");

        BrowseCard card = new BrowseCard(new FormatMarks(List.of(new StubFormat("maven", "/maven/", null))));

        assertThat(card.model(store)).isEqualTo(card.model(store));
    }

    @Test
    void an_empty_repository_offers_no_namespaces_at_all() throws IOException {
        // Nothing published means nothing to attribute: the card answers no rows, so the fragment shows its empty
        // state rather than a row with a neutral box on it.
        assertThat(new BrowseCard(new FormatMarks(List.of())).model(store).namespaces()).isEmpty();
    }

    /** The mark one namespace carries, so a claim about attribution reads as one. */
    private static String mark(List<BrowseCard.Namespace> namespaces, String name) {
        return namespaces.stream().filter(namespace -> namespace.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no namespace " + name + " in " + namespaces))
                .markSvg();
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
        public void serve(FormatExchange exchange, ArtifactStore store) {
            throw new UnsupportedOperationException("the browse card never dispatches a request");
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
