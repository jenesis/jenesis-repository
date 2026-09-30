package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.search.LicenseFacet;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import io.micrometer.observation.ObservationRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The console repository search in its two modes, and the licence inventory the full-text index backs. With a
 * repository's full-text search on and its index answering, {@link RepositoryBrowse#search} takes its match set from
 * the index - honouring the {@code license:}/{@code category:} filter tokens the licence inventory drills down with -
 * led by the name lookup's hits for the same query, and places each hit in the browse tree;
 * {@link RepositoryBrowse#licenses} rolls the index's facets into the console's per-category and per-SPDX-id counts.
 * With it off, or its index not built, search is the lookup by name and the licence inventory reports itself
 * not-indexed rather than showing a false clean bill.
 */
public class RepositoryLicenseInventoryTest {

    /** A repository that has asked for a full-text index. */
    private static final UnaryOperator<String> FULL_TEXT = key -> SearchMode.SETTING.equals(key) ? "true" : null;

    /** A repository with nothing set, which answers by name. */
    private static final UnaryOperator<String> NOTHING_SET = key -> null;

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore repo = store.scope("acme").scope("releases");
        Publication publication = new Publication(repo);
        publication.link("/maven/org/acme/lib/1.0/lib-1.0.jar",
                publication.storeBlob(new ByteArrayInputStream("a library jar".getBytes(UTF_8))));
        publication.link("/maven/org/acme/tool/2.0/tool-2.0.jar",
                publication.storeBlob(new ByteArrayInputStream("a tool jar".getBytes(UTF_8))));
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repo);
        inventory.record("Maven", "org.acme:lib", "1.0", Instant.parse("2026-06-01T00:00:00Z"));
        inventory.record("Maven", "org.acme:tool", "2.0", Instant.parse("2026-06-02T00:00:00Z"));
    }

    private RepositoryBrowse admin(Optional<SearchQueryProvider> search) {
        return new RepositoryBrowse(store, () -> "acme", ObservationRegistry.NOOP, search);
    }

    private static List<String> coordinates(RepositoryBrowse.SearchPage page) {
        return page.results().stream().map(RepositoryBrowse.SearchResult::coordinate).toList();
    }

    @Test
    void full_text_takes_its_match_set_from_the_index_led_by_the_name_lookup() throws IOException {
        // The index returns tool for "org.acme:l", which no name starting with that would match - so tool among the
        // results proves the index's match set drove the search. lib leads, from the name lookup every first page of a
        // full-text answer starts with, so what was just published is found before the index has caught up with it.
        FakeSearch index = new FakeSearch(List.of(SearchQuery.Hit.coordinate("Maven", "org.acme:tool", "2.0")),
                List.of());
        RepositoryBrowse.SearchPage page = admin(Optional.of(index)).search("releases", FULL_TEXT, "org.acme:l",
                null);
        assertThat(page.mode()).isEqualTo(SearchMode.FULL_TEXT);
        assertThat(page.indexed()).isTrue();
        assertThat(coordinates(page)).containsExactly("org.acme:lib", "org.acme:tool");
        assertThat(page.results()).filteredOn(hit -> hit.coordinate().equals("org.acme:tool")).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.version()).isEqualTo("2.0");
                    assertThat(hit.ecosystem()).isEqualTo("Maven");
                    assertThat(hit.location()).isEqualTo("/maven/org/acme/tool/2.0");
                });
        // The raw query and the tenant/repository scope reached the index unchanged, so the provider can cache and
        // filter on them.
        assertThat(index.lastQuery).isEqualTo("org.acme:l");
        assertThat(index.lastScope).isEqualTo("acme/releases");
    }

    @Test
    void full_text_passes_the_licence_inventorys_filter_token_to_the_index() throws IOException {
        // A category: filter token no coordinate name starts with: a lookup by name would find nothing, so lib proves
        // the token reached the index, which resolves it against the stored licence fields.
        FakeSearch index = new FakeSearch(List.of(SearchQuery.Hit.coordinate("Maven", "org.acme:lib", "1.0")),
                List.of());
        assertThat(coordinates(admin(Optional.of(index)).search("releases", FULL_TEXT, "category:permissive", null)))
                .containsExactly("org.acme:lib");
        assertThat(index.lastQuery).isEqualTo("category:permissive");
    }

    @Test
    void full_text_answers_by_name_until_its_index_is_built() throws IOException {
        // The index is installed and the repository asked for it, but it is not built (search answers empty): the
        // name lookup answers meanwhile, and says so - an absent page, not a present-but-empty one.
        RepositoryBrowse.SearchPage page = admin(Optional.of(new FakeSearch(null, null)))
                .search("releases", FULL_TEXT, "org.acme:l", null);
        assertThat(page.mode()).isEqualTo(SearchMode.FULL_TEXT);
        assertThat(page.indexed()).isFalse();
        assertThat(coordinates(page)).containsExactly("org.acme:lib");
    }

    @Test
    void a_repository_with_nothing_set_looks_up_by_name_and_never_asks_the_index() throws IOException {
        FakeSearch index = new FakeSearch(List.of(SearchQuery.Hit.coordinate("Maven", "org.acme:lib", "1.0")),
                List.of());
        RepositoryBrowse admin = admin(Optional.of(index));
        RepositoryBrowse.SearchPage page = admin.search("releases", NOTHING_SET, "org.acme:t", null);
        assertThat(page.mode()).isEqualTo(SearchMode.NAME);
        assertThat(coordinates(page)).containsExactly("org.acme:tool");
        assertThat(index.lastQuery).as("the index is not asked").isNull();
        assertThat(coordinates(admin.search("releases", NOTHING_SET, "", null))).as("an empty query lists all")
                .containsExactly("org.acme:lib", "org.acme:tool");
        assertThat(coordinates(admin(Optional.empty()).search("releases", FULL_TEXT, "org.acme:t", null)))
                .as("and a composition with no index answers by name whatever the setting asks")
                .containsExactly("org.acme:tool");
    }

    @Test
    void licenses_rolls_the_index_facets_into_per_category_and_per_spdx_columns() throws IOException {
        // The index's facets, split by kind into the two console columns: categories and resolved SPDX ids, each with
        // its coordinate count, reported as indexed so the screen renders the tables rather than the not-indexed note.
        FakeSearch index = new FakeSearch(List.of(), List.of(
                new LicenseFacet(LicenseFacet.CATEGORY, "permissive", 3),
                new LicenseFacet(LicenseFacet.CATEGORY, "strong-copyleft", 1),
                new LicenseFacet(LicenseFacet.LICENSE, "Apache-2.0", 2),
                new LicenseFacet(LicenseFacet.LICENSE, "GPL-3.0-only", 1)));
        RepositoryBrowse.LicenseInventory inventory = admin(Optional.of(index)).licenses("releases", FULL_TEXT);
        assertThat(inventory.indexed()).isTrue();
        assertThat(inventory.categories()).extracting(RepositoryBrowse.LicenseCount::value, RepositoryBrowse.LicenseCount::count)
                .containsExactly(tuple("permissive", 3L), tuple("strong-copyleft", 1L));
        assertThat(inventory.licenses()).extracting(RepositoryBrowse.LicenseCount::value, RepositoryBrowse.LicenseCount::count)
                .containsExactly(tuple("Apache-2.0", 2L), tuple("GPL-3.0-only", 1L));
    }

    @Test
    void licenses_report_not_indexed_while_full_text_is_off_unbuilt_or_absent() throws IOException {
        FakeSearch built = new FakeSearch(List.of(), List.of(new LicenseFacet(LicenseFacet.CATEGORY, "permissive", 3)));
        assertThat(admin(Optional.of(built)).licenses("releases", NOTHING_SET).indexed())
                .as("off: the repository has no index to count its licences").isFalse();
        RepositoryBrowse.LicenseInventory unbuilt = admin(Optional.of(new FakeSearch(null, null)))
                .licenses("releases", FULL_TEXT);
        assertThat(unbuilt.indexed()).as("on, not built yet").isFalse();
        assertThat(unbuilt.categories()).isEmpty();
        assertThat(unbuilt.licenses()).isEmpty();
        assertThat(admin(Optional.empty()).licenses("releases", FULL_TEXT).indexed()).as("no index installed")
                .isFalse();
    }

    /** A canned search index for the test: it returns a fixed match set for {@link #search} and fixed facets for
     *  {@link #licenses} (both {@code null} to model a not-yet-built index, which the SPI now says with an empty
     *  {@link Optional} -), and captures the last query and scope so a test can assert RepositoryBrowse passed
     *  them through unchanged. */
    private static final class FakeSearch implements SearchQueryProvider, SearchQuery {

        private final List<SearchQuery.Hit> matches;
        private final List<LicenseFacet> facets;
        private String lastQuery;
        private String lastScope;

        FakeSearch(List<SearchQuery.Hit> matches, List<LicenseFacet> facets) {
            this.matches = matches;
            this.facets = facets;
        }

        @Override
        public SearchQuery over(ArtifactStore store, String scope) {
            this.lastScope = scope;
            return this;
        }

        @Override
        public Optional<Hits> search(String query, String cursor, int limit) {
            this.lastQuery = query;
            return Optional.ofNullable(matches).map(Hits::last);
        }

        @Override
        public Optional<List<LicenseFacet>> licenses() {
            return Optional.ofNullable(facets);
        }
    }
}
