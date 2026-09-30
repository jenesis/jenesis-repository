package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.AboutSection;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the index finds a package by, each field for what a person types: the name, for the package they know; the
 * description, for the one whose name escapes them but whose purpose does not; the keywords its author filed it
 * under; and the people it credits. All of it is read from what the publish recorded in the version's document, and
 * the licence filters the licence inventory drills down with keep working beside it.
 */
class SearchFieldsTest {

    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("app");
        publish("org.example:jsonic", "1.0", new AboutSection.About("A small, fast JSON parser (streaming).",
                List.of("json", "serialisation"), List.of("Ada Lovelace")), "Apache-2.0");
        publish("org.example:yamlish", "2.0", new AboutSection.About("Reads YAML documents.", List.of("yaml"),
                List.of("Grace Hopper")), "MIT");
        publish("org.example:bare", "3.0", null, null);
        new SearchIndexTask(Duration.ofMinutes(10)).repository(context());
    }

    private void publish(String coordinate, String version, AboutSection.About about, String licence)
            throws IOException {
        String path = "/maven/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        var recording = new StoreRepositoryInventory(store).recording("maven", coordinate, version, false, NOW);
        if (about != null) {
            recording.about(about);
        }
        if (licence != null) {
            recording.licenses(List.of(new LicenseInventory.Declared(licence, null)));
        }
        recording.commit();
    }

    private List<String> find(String query) throws IOException {
        return new LuceneSearchQueryProvider(Duration.ZERO).over(store, "default/app")
                .search(query, null, SearchQuery.MAX_PAGE).orElseThrow()
                .hits().stream().map(SearchQuery.Hit::display).toList();
    }

    @Test
    void a_word_of_the_description_finds_the_package_whatever_its_punctuation() throws IOException {
        assertThat(find("parser")).containsExactly("org.example:jsonic:1.0");
        assertThat(find("streaming")).as("a word in parentheses is still the word").containsExactly(
                "org.example:jsonic:1.0");
        assertThat(find("fast json")).as("every word must match, across the description")
                .containsExactly("org.example:jsonic:1.0");
    }

    @Test
    void a_keyword_and_an_author_find_the_package() throws IOException {
        assertThat(find("serialisation")).containsExactly("org.example:jsonic:1.0");
        assertThat(find("hopper")).as("a person's name, however it is cased").containsExactly(
                "org.example:yamlish:2.0");
    }

    @Test
    void a_name_answers_before_anything_the_text_mentions() throws IOException {
        // "yaml" is a keyword of yamlish and a segment of no other name; "json" is jsonic's keyword and a word of its
        // description, but a query that is a package's name answers that package alone.
        assertThat(find("yamlish")).containsExactly("org.example:yamlish:2.0");
        assertThat(find("example")).as("a name segment every package shares lists them all")
                .containsExactly("org.example:bare:3.0", "org.example:jsonic:1.0", "org.example:yamlish:2.0");
    }

    @Test
    void the_licence_inventorys_drill_down_still_filters() throws IOException {
        assertThat(find("license:mit")).containsExactly("org.example:yamlish:2.0");
        assertThat(find("category:permissive")).containsExactly("org.example:jsonic:1.0", "org.example:yamlish:2.0");
    }

    private RepositoryContext context() {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public String repository() {
                return "app";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> SearchMode.SETTING.equals(key) ? "true" : null;
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
