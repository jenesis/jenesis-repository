package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.inventory.StoreRepositoryInventory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim on the next generation that never committed belongs to a rebuild that died between writing its manifest and
 * cutting over. Once it is older than {@code search-index-claim} the next rebuild takes the generation over and
 * commits it, so the index cannot wedge behind a dead node; while it is younger the rebuild commits nothing, so two
 * live rebuilds never both cut over one generation.
 */
class SearchStaleClaimTest {

    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void a_dead_rebuilds_claim_is_taken_over_once_stale_and_a_live_ones_is_left_alone() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope("app");
        publish(store, "org.example:one", "1.0");
        sweep(store, "PT1H");
        assertThat(committed(store)).isEqualTo(1);

        // A live rebuild's claim on generation 2, just written: the next rebuild must not commit over it.
        claim(store, 2, Instant.now());
        publish(store, "org.example:two", "2.0");
        sweep(store, "PT1H");
        assertThat(committed(store)).as("a live claim on the next generation is left to its holder").isEqualTo(1);

        // The same claim from a rebuild that died an hour ago: past the window, the next rebuild takes it over.
        claim(store, 2, Instant.now().minus(Duration.ofHours(2)));
        sweep(store, "PT1H");
        assertThat(committed(store)).as("a stale claim is taken over and its generation committed").isEqualTo(2);
        assertThat(hits(store, "example")).as("and the committed generation answers")
                .containsExactly("org.example:one:1.0", "org.example:two:2.0");
    }

    /** An uncommitted claim on {@code generation}, as a rebuild writes it before cutting over: the claim instant and no
     *  segment lines. */
    private static void claim(ArtifactStore store, int generation, Instant claimed) throws IOException {
        store.delete("index/search/" + generation + ".manifest");
        assertThat(store.writeVersioned("index/search/" + generation + ".manifest",
                ("claimed " + claimed.toEpochMilli() + "\n").getBytes(StandardCharsets.UTF_8), null)).isTrue();
    }

    private static int committed(ArtifactStore store) throws IOException {
        String manifest = new String(store.readVersioned("index/search/current").orElseThrow().content(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("generation (\\d+)").matcher(manifest);
        assertThat(matcher.find()).as("the committed manifest names its generation: %s", manifest).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static void publish(ArtifactStore store, String coordinate, String version) throws IOException {
        String path = "/maven/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record("maven", coordinate, version, false, NOW);
    }

    private static List<String> hits(ArtifactStore store, String text) throws IOException {
        SearchQuery query = new LuceneSearchQueryProvider(Duration.ZERO).over(store, "default/app");
        return query.search(text, null, SearchQuery.MAX_PAGE)
                .map(page -> page.hits().stream().map(SearchQuery.Hit::display).toList()).orElse(null);
    }

    /** One full rebuild with the claim window {@code claim}. */
    private static void sweep(ArtifactStore store, String claim) throws IOException {
        new SearchIndexTask(Duration.ofMinutes(10)).repository(new RepositoryContext() {
            @Override
            public TenantView tenantView() {
                return TenantView.NONE;
            }

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
                return key -> switch (key) {
                    case "search-incremental" -> "false";   // every sweep a full rebuild, which is what claims
                    case "search-index-claim" -> claim;
                    case SearchMode.SETTING -> "true";
                    default -> null;
                };
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        });
    }
}
