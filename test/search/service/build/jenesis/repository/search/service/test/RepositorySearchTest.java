package build.jenesis.repository.search.service.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.search.service.RepositorySearch;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one search in both modes over a real filesystem store: the lookup by name - its match, its screen, its cursor,
 * its budget and what it costs - and the full-text answer from an installed index, led by the lookup.
 */
class RepositorySearchTest {

    private static final Instant PUBLISHED = Instant.parse("2026-09-30T00:00:00Z");

    private static final UnaryOperator<String> BY_NAME = key -> null;

    private static final UnaryOperator<String> FULL_TEXT = key -> SearchMode.SETTING.equals(key) ? "true" : null;

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme")
                .scope("releases");
    }

    private void publish(ArtifactStore into, String coordinate, String version) throws IOException {
        String path = SearchTestFormat.path(coordinate, version);
        Publication publication = new Publication(into);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
        new StoreRepositoryInventory(into).record(SearchTestFormat.ECOSYSTEM, coordinate, version, PUBLISHED);
    }

    /** A file no coordinate names, as a raw upload is, published at {@code path}; answers its content hash. */
    private String upload(ArtifactStore into, String path) throws IOException {
        Publication publication = new Publication(into);
        String hash = publication.storeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        return hash;
    }

    private static List<String> displays(RepositorySearch.Answer answer) {
        return answer.hits().stream().map(SearchQuery.Hit::display).toList();
    }

    private RepositorySearch.Answer byName(String query, String cursor, int limit) throws IOException {
        return new RepositorySearch(Optional.empty()).search(store, "acme/releases", BY_NAME, query, cursor, limit);
    }

    @Test
    void a_lookup_finds_the_versions_of_every_coordinate_whose_name_starts_with_the_query() throws IOException {
        publish(store, "org.acme.lib", "1.0");
        publish(store, "org.acme.lib", "2.0");
        publish(store, "org.acme.tool", "1.0");
        publish(store, "org.other.lib", "1.0");

        RepositorySearch.Answer answer = byName("org.acme.l", null, 10);

        assertThat(answer.mode()).isEqualTo(SearchMode.NAME);
        assertThat(answer.indexed()).isFalse();
        assertThat(displays(answer)).containsExactly("org.acme.lib:1.0", "org.acme.lib:2.0");
        assertThat(answer.hits()).allSatisfy(hit -> assertThat(hit.ecosystem()).isEqualTo(SearchTestFormat.ECOSYSTEM));
        assertThat(answer.truncated()).isFalse();
        assertThat(displays(byName("", null, 10))).as("an empty query is every name").hasSize(4);
        assertThat(displays(byName("lib", null, 10))).as("the start of the name, not a word inside it").isEmpty();
    }

    @Test
    void a_file_no_coordinate_names_is_found_by_the_start_of_its_path() throws IOException {
        upload(store, "/files/installers/setup-1.0.bin");
        upload(store, "/files/installers/setup-2.0.bin");
        upload(store, "/files/installers/uninstall.bin");
        upload(store, "/files/notes.txt");
        publish(store, "org.acme.lib", "1.0");

        assertThat(displays(byName("files/installers/setup", null, 10)))
                .containsExactly("/files/installers/setup-1.0.bin", "/files/installers/setup-2.0.bin");
        assertThat(displays(byName("installers/setup", null, 10)))
                .as("as the repository's URL reads it, without the mount its format keeps the file under")
                .containsExactly("/files/installers/setup-1.0.bin", "/files/installers/setup-2.0.bin");
        assertThat(displays(byName("/files/installers/setup-2", null, 10))).as("with the leading slash as well")
                .containsExactly("/files/installers/setup-2.0.bin");
        assertThat(displays(byName("files/inst", null, 10))).as("a folder's start reaches every file under it")
                .containsExactly("/files/installers/setup-1.0.bin", "/files/installers/setup-2.0.bin",
                        "/files/installers/uninstall.bin");
        assertThat(displays(byName("files/installers/", null, 10))).as("a folder named to its slash")
                .containsExactly("/files/installers/setup-1.0.bin", "/files/installers/setup-2.0.bin",
                        "/files/installers/uninstall.bin");
        assertThat(displays(byName("files//installers", null, 10))).as("what names no stored file finds none")
                .isEmpty();
        assertThat(displays(byName("files/../files", null, 10))).isEmpty();
        assertThat(displays(byName("test/org.acme", null, 10)))
                .as("a file a coordinate names is found by the coordinate, never a second time by its path")
                .isEmpty();
        assertThat(displays(byName("", null, 10))).as("an empty query is every coordinate, then every such file")
                .containsExactly("org.acme.lib:1.0", "/files/installers/setup-1.0.bin",
                        "/files/installers/setup-2.0.bin", "/files/installers/uninstall.bin", "/files/notes.txt");
    }

    @Test
    void a_held_file_is_no_more_findable_than_it_is_served() throws IOException {
        upload(store, "/files/installers/setup-1.0.bin");
        Withheld.mark(store, upload(store, "/files/installers/setup-2.0.bin"));

        assertThat(displays(byName("files/installers", null, 10)))
                .containsExactly("/files/installers/setup-1.0.bin");
    }

    @Test
    void the_cursor_runs_on_from_the_coordinates_into_the_files_and_reaches_each_once() throws IOException {
        publish(store, "files.lib", "1.0");
        publish(store, "files.lib", "2.0");
        for (int index = 0; index < 5; index++) {
            upload(store, "/files/build-" + index + ".zip");
        }

        List<String> walked = new ArrayList<>();
        RepositorySearch.Answer page = byName("files", null, 2);
        walked.addAll(displays(page));
        while (page.truncated()) {
            page = byName("files", page.nextCursor(), 2);
            assertThat(page.hits()).as("no page comes back empty").isNotEmpty();
            walked.addAll(displays(page));
        }
        assertThat(walked).doesNotHaveDuplicates().containsExactly("files.lib:1.0", "files.lib:2.0",
                "/files/build-0.zip", "/files/build-1.zip", "/files/build-2.zip", "/files/build-3.zip",
                "/files/build-4.zip");
    }

    @Test
    void a_path_lookup_costs_the_same_however_many_other_files_the_repository_holds() throws IOException {
        // The two-size ratio again, for the path half: the files before the query's start are skipped by seeking to
        // it and the ones after it end the scan, so a lookup in a folder of three hundred others reads as much as in
        // a folder of ten.
        long small = pathLookupCost("files-small", 10);
        long large = pathLookupCost("files-large", 300);

        assertThat(large).as("the path lookup's store operations do not grow with the folder").isEqualTo(small);
    }

    private long pathLookupCost(String repository, int others) throws IOException {
        ArtifactStore scoped = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme")
                .scope(repository);
        upload(scoped, "/files/installers/setup-1.0.bin");
        for (int index = 0; index < others; index++) {
            upload(scoped, String.format(Locale.ROOT, "/files/installers/a%04d.bin", index));
            upload(scoped, String.format(Locale.ROOT, "/files/installers/z%04d.bin", index));
        }
        AtomicLong operations = new AtomicLong();
        FaultInjectingStore counted = FaultInjectingStore.wrap(scoped).tracing((op, key) -> operations.incrementAndGet());
        RepositorySearch.Answer answer = new RepositorySearch(Optional.empty())
                .search(counted, "acme/" + repository, BY_NAME, "files/installers/setup", null, 10);
        assertThat(displays(answer)).containsExactly("/files/installers/setup-1.0.bin");
        return operations.get();
    }

    @Test
    void a_lookup_reads_the_query_as_typed_and_in_lower_case() throws IOException {
        publish(store, "demo", "1.0");
        publish(store, "Demonstration", "1.0");

        assertThat(displays(byName("Demo", null, 10))).as("a format that folds a name's case keeps the folded form")
                .containsExactly("Demonstration:1.0", "demo:1.0");
    }

    @Test
    void a_held_version_is_no_more_findable_than_it_is_served() throws IOException {
        publish(store, "org.acme.lib", "1.0");
        publish(store, "org.acme.lib", "2.0");
        Publication publication = new Publication(store);
        publication.link("/quarantine" + SearchTestFormat.path("org.acme.lib", "2.0"), "e".repeat(64));

        assertThat(displays(byName("org.acme", null, 10))).containsExactly("org.acme.lib:1.0");
    }

    @Test
    void the_cursor_reaches_every_match_exactly_once_and_a_foreign_one_is_refused() throws IOException {
        for (int version = 0; version < 4; version++) {
            publish(store, "org.acme.lib", "1." + version);
        }
        publish(store, "org.acme.tool", "1.0");
        publish(store, "org.acme.util", "1.0");
        publish(store, "org.zzz.other", "1.0");

        List<String> walked = new ArrayList<>();
        RepositorySearch.Answer page = byName("org.acme", null, 3);
        walked.addAll(displays(page));
        assertThat(page.hits()).hasSize(3);
        assertThat(page.truncated()).as("more remain, and the page says so").isTrue();
        while (page.truncated()) {
            page = byName("org.acme", page.nextCursor(), 3);
            walked.addAll(displays(page));
        }
        assertThat(walked).doesNotHaveDuplicates().containsExactly("org.acme.lib:1.0", "org.acme.lib:1.1",
                "org.acme.lib:1.2", "org.acme.lib:1.3", "org.acme.tool:1.0", "org.acme.util:1.0");

        assertThatThrownBy(() -> byName("org.acme", "no-cursor-of-ours", 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_lookup_that_spends_its_budget_answers_cut_short_and_resumes_where_it_stopped() throws IOException {
        // Each coordinate costs the budget itself and its one version, so a budget of a thousand looks at five
        // hundred coordinates: a page asking for more answers with what it found and a cursor, never a short list
        // that reads as the whole answer.
        int coordinates = 600;
        for (int index = 0; index < coordinates; index++) {
            publish(store, String.format(Locale.ROOT, "pkg%04d", index), "1.0");
        }

        RepositorySearch.Answer first = byName("pkg", null, SearchQuery.MAX_PAGE);
        assertThat(first.hits()).as("cut short by the budget, below the page it asked for")
                .hasSizeLessThan(coordinates);
        assertThat(first.truncated()).isTrue();

        List<String> walked = new ArrayList<>(displays(first));
        RepositorySearch.Answer page = first;
        while (page.truncated()) {
            page = byName("pkg", page.nextCursor(), SearchQuery.MAX_PAGE);
            walked.addAll(displays(page));
        }
        assertThat(walked).doesNotHaveDuplicates().hasSize(coordinates);
    }

    @Test
    void a_lookup_costs_the_same_however_much_else_the_repository_holds() throws IOException {
        // The bounded-read rule as a two-size ratio: the same lookup over a repository holding ten other packages and
        // over one holding three hundred reads exactly as much, because it reads one run of a sorted listing.
        long small = lookupCost("small", 10);
        long large = lookupCost("large", 300);

        assertThat(large).as("the lookup's store operations do not grow with the repository").isEqualTo(small);
        assertThat(small).as("a page of the ecosystems, of the run, of its versions, the version documents and the "
                + "withheld screen - a handful").isLessThan(20);
    }

    private long lookupCost(String repository, int others) throws IOException {
        ArtifactStore scoped = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("acme")
                .scope(repository);
        publish(scoped, "org.acme.lib", "1.0");
        for (int index = 0; index < others; index++) {
            publish(scoped, String.format(Locale.ROOT, "aa.filler%04d", index), "1.0");
        }
        AtomicLong operations = new AtomicLong();
        FaultInjectingStore counted = FaultInjectingStore.wrap(scoped).tracing((op, key) -> operations.incrementAndGet());
        RepositorySearch.Answer answer = new RepositorySearch(Optional.empty())
                .search(counted, "acme/" + repository, BY_NAME, "org.acme.lib", null, 10);
        assertThat(displays(answer)).containsExactly("org.acme.lib:1.0");
        return operations.get();
    }

    @Test
    void full_text_answers_from_the_index_led_by_the_lookup_and_screened_the_same_way() throws IOException {
        publish(store, "org.acme.lib", "1.0");
        publish(store, "org.acme.tool", "1.0");
        publish(store, "org.acme.held", "1.0");
        new Publication(store).link("/quarantine" + SearchTestFormat.path("org.acme.held", "1.0"), "e".repeat(64));
        StandInIndex index = new StandInIndex(List.of(
                SearchQuery.Hit.coordinate(SearchTestFormat.ECOSYSTEM, "org.acme.held", "1.0"),
                SearchQuery.Hit.coordinate(SearchTestFormat.ECOSYSTEM, "org.acme.tool", "1.0")), "next-page");
        RepositorySearch search = new RepositorySearch(Optional.of(index));

        RepositorySearch.Answer answer = search.search(store, "acme/releases", FULL_TEXT, "org.acme.l", null, 10);

        assertThat(answer.mode()).isEqualTo(SearchMode.FULL_TEXT);
        assertThat(answer.indexed()).isTrue();
        assertThat(displays(answer)).as("the lookup leads, the index follows, and the held version is screened out")
                .containsExactly("org.acme.lib:1.0", "org.acme.tool:1.0");
        assertThat(index.lastScope).isEqualTo("acme/releases");

        search.search(store, "acme/releases", FULL_TEXT, "org.acme.l", answer.nextCursor(), 10);
        assertThat(index.lastCursor).as("the index's own cursor comes back to it").isEqualTo("next-page");
    }

    @Test
    void full_text_answers_by_name_until_its_index_is_built_and_a_name_cursor_stays_a_name_cursor()
            throws IOException {
        for (int version = 0; version < 3; version++) {
            publish(store, "org.acme.lib", "1." + version);
        }
        StandInIndex unbuilt = new StandInIndex(null, null);
        RepositorySearch.Answer first = new RepositorySearch(Optional.of(unbuilt))
                .search(store, "acme/releases", FULL_TEXT, "org.acme", null, 2);
        assertThat(first.mode()).isEqualTo(SearchMode.FULL_TEXT);
        assertThat(first.indexed()).as("the name lookup answered meanwhile").isFalse();
        assertThat(displays(first)).containsExactly("org.acme.lib:1.0", "org.acme.lib:1.1");

        StandInIndex built = new StandInIndex(List.of(), null);
        RepositorySearch.Answer second = new RepositorySearch(Optional.of(built))
                .search(store, "acme/releases", FULL_TEXT, "org.acme", first.nextCursor(), 2);
        assertThat(displays(second)).as("a page begun by name resumes by name, even once the index is built")
                .containsExactly("org.acme.lib:1.2");
        assertThat(built.lastQuery).isNull();
    }

    @Test
    void by_name_the_index_is_never_asked() throws IOException {
        publish(store, "org.acme.lib", "1.0");
        StandInIndex index = new StandInIndex(List.of(), null);
        RepositorySearch search = new RepositorySearch(Optional.of(index));

        search.search(store, "acme/releases", BY_NAME, "org.acme", null, 10);

        assertThat(index.lastQuery).isNull();
    }

    /** An installed index that answers a fixed page, or has not been built when it holds none. */
    private static final class StandInIndex implements SearchQueryProvider, SearchQuery {

        @Override
        public void close() {
        }

        private final List<Hit> hits;
        private final String next;
        private String lastQuery;
        private String lastCursor;
        private String lastScope;

        StandInIndex(List<Hit> hits, String next) {
            this.hits = hits;
            this.next = next;
        }

        @Override
        public SearchQuery over(ArtifactStore store, String scope) {
            lastScope = scope;
            return this;
        }

        @Override
        public Optional<Hits> search(String query, String cursor, int limit) {
            lastQuery = query;
            lastCursor = cursor;
            return hits == null ? Optional.empty() : Optional.of(new Hits(hits, Optional.ofNullable(next)));
        }
    }
}
