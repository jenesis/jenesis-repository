package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.search.LicenseFacet;
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
 * The console repository search routed through the installed search index, and the license inventory it backs.
 * When a {@link SearchQueryProvider} is present, {@link RepositoryBrowse#search} takes its match set from the index -
 * honouring {@code license:}/{@code category:} filter tokens the live substring scan cannot - and enriches each hit
 * with the ecosystem and browse folder joined from the release index; {@link RepositoryBrowse#licenses} rolls the
 * index's declared-license fields into the console's per-category and per-SPDX-id facet counts. With no provider (or an
 * index that has not been built) both degrade gracefully: search falls back to the substring scan, and the license
 * inventory reports itself not-indexed rather than showing a false clean bill.
 */
public class RepositoryLicenseInventoryTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
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

    @Test
    void search_takes_its_match_set_from_the_index_enriched_from_the_release_index() throws IOException {
        // The index returns tool; the substring "lib" would not have matched it - so tool among the results proves
        // the index's match set drove the search, not a live scan. The hit is still enriched from the release index:
        // ecosystem Maven and the browse folder the Maven layout places the coordinate at. lib is there as well, from
        // the newest-releases window every search leads with, so what was just published is found before the index
        // has caught up with it.
        FakeSearch index = new FakeSearch(List.of("org.acme:tool:2.0"), List.of());
        List<RepositoryBrowse.SearchResult> results = admin(Optional.of(index)).search("releases", "lib").results();
        assertThat(results).extracting(RepositoryBrowse.SearchResult::coordinate)
                .containsExactly("org.acme:lib", "org.acme:tool");
        assertThat(results).filteredOn(hit -> hit.coordinate().equals("org.acme:tool")).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.version()).isEqualTo("2.0");
                    assertThat(hit.ecosystem()).isEqualTo("Maven");
                    assertThat(hit.location()).isEqualTo("/maven/org/acme/tool/2.0");
                });
        // The raw query and the tenant/repository scope reached the index unchanged, so the provider can cache and
        // filter on them.
        assertThat(index.lastQuery).isEqualTo("lib");
        assertThat(index.lastScope).isEqualTo("acme/releases");
    }

    @Test
    void search_passes_a_license_filter_token_to_the_index_the_substring_scan_could_not_honour() throws IOException {
        // A category: filter token no coordinate string contains: the substring scan would match nothing, so a result
        // of lib proves the token was passed to the index (which resolves it against the stored license fields) rather
        // than filtered by the fall-back scan.
        FakeSearch index = new FakeSearch(List.of("org.acme:lib:1.0"), List.of());
        assertThat(admin(Optional.of(index)).search("releases", "category:permissive").results())
                .extracting(RepositoryBrowse.SearchResult::coordinate).containsExactly("org.acme:lib");
        assertThat(index.lastQuery).isEqualTo("category:permissive");
    }

    @Test
    void search_falls_back_to_the_substring_scan_when_the_index_has_no_usable_snapshot() throws IOException {
        // The provider is installed but its index has not been built (search returns null): RepositoryBrowse falls back
        // to the live substring scan, so "lib" matches the lib coordinate the scan finds - the absent page is the
        // fall-through signal, not a present-but-empty page the scan would have filled.
        FakeSearch index = new FakeSearch(null, null);
        assertThat(admin(Optional.of(index)).search("releases", "lib").results())
                .extracting(RepositoryBrowse.SearchResult::coordinate).containsExactly("org.acme:lib");
    }

    @Test
    void search_without_the_index_module_is_the_live_substring_scan() throws IOException {
        // No provider at all: the search is the built-in substring scan over the published coordinates, and the console
        // hides the license-inventory link because the facets need the index.
        RepositoryBrowse admin = admin(Optional.empty());
        assertThat(admin.searchIndexAvailable()).isFalse();
        assertThat(admin.search("releases", "tool").results())
                .extracting(RepositoryBrowse.SearchResult::coordinate).containsExactly("org.acme:tool");
        assertThat(admin.search("releases", "").results()).as("empty query lists all")
                .extracting(RepositoryBrowse.SearchResult::coordinate).containsExactly("org.acme:lib", "org.acme:tool");
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
        RepositoryBrowse admin = admin(Optional.of(index));
        assertThat(admin.searchIndexAvailable()).isTrue();
        RepositoryBrowse.LicenseInventory inventory = admin.licenses("releases");
        assertThat(inventory.indexed()).isTrue();
        assertThat(inventory.categories()).extracting(RepositoryBrowse.LicenseCount::value, RepositoryBrowse.LicenseCount::count)
                .containsExactly(tuple("permissive", 3L), tuple("strong-copyleft", 1L));
        assertThat(inventory.licenses()).extracting(RepositoryBrowse.LicenseCount::value, RepositoryBrowse.LicenseCount::count)
                .containsExactly(tuple("Apache-2.0", 2L), tuple("GPL-3.0-only", 1L));
    }

    @Test
    void licenses_reports_not_indexed_when_the_index_has_no_snapshot_or_the_module_is_absent() throws IOException {
        // The provider is installed but the index is not built (licenses returns null): the inventory is reported
        // not-indexed with empty facets - the module is present (so the link shows) but this repository has no facets
        // yet, the same signal search gives.
        RepositoryBrowse.LicenseInventory unbuilt = admin(Optional.of(new FakeSearch(null, null))).licenses("releases");
        assertThat(unbuilt.indexed()).isFalse();
        assertThat(unbuilt.categories()).isEmpty();
        assertThat(unbuilt.licenses()).isEmpty();
        // No provider at all: also not-indexed, and the link is hidden.
        RepositoryBrowse.LicenseInventory absent = admin(Optional.empty()).licenses("releases");
        assertThat(absent.indexed()).isFalse();
        assertThat(absent.categories()).isEmpty();
        assertThat(absent.licenses()).isEmpty();
    }

    /** A canned search index for the test: it returns a fixed match set for {@link #search} and fixed facets for
     *  {@link #licenses} (both {@code null} to model a not-yet-built index, which the SPI now says with an empty
     *  {@link Optional} -), and captures the last query and scope so a test can assert RepositoryBrowse passed
     *  them through unchanged. */
    private static final class FakeSearch implements SearchQueryProvider, SearchQuery {

        private final List<String> matches;
        private final List<LicenseFacet> facets;
        private String lastQuery;
        private String lastScope;

        FakeSearch(List<String> matches, List<LicenseFacet> facets) {
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
