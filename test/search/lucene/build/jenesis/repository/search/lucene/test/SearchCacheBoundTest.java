package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.inventory.StoreRepositoryInventory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bound on the per-scope searcher cache: the provider once kept a {@code LuceneSearcher} - a whole in-heap index -
 * for every scope ever searched, so a fleet's heap grew with the number of repositories. These assert the replacement
 * size/count-weighted LRU stays within its cap however many scopes are queried, and that an idle scope is dropped and
 * reloads from the store snapshot transparently on its next query (the index is derived data, the reload is the usual
 * TTL refresh).
 */
class SearchCacheBoundTest {

    /** How many idle-window sweeps the eviction cell drives before giving up. Attempts, never a deadline. */
    private static final int SWEEPS = 100;

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store(String tenant, String repository) {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope(tenant).scope(repository);
    }

    private void publish(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        String path = "/" + ecosystem + "/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record(ecosystem, coordinate, version, false, NOW);
    }

    private void sweep(ArtifactStore store) throws IOException {
        new SearchIndexTask(INTERVAL).repository(context(store));
    }

    @Test
    void the_cache_stays_bounded_across_many_scopes() {
        LuceneSearchQueryProvider provider = new LuceneSearchQueryProvider(
                Duration.ZERO, 4, Long.MAX_VALUE, Duration.ofHours(1));
        ArtifactStore store = store("default", "app");
        for (int scope = 0; scope < 200; scope++) {
            provider.over(store, "default/repo-" + scope);
        }
        assertThat(provider.residentScopes())
                .as("the LRU cannot grow past its scope-count cap however many repositories are searched")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void a_low_heap_cap_evicts_even_below_the_scope_count() throws IOException {
        // Two real, loaded indexes exceed a one-byte heap cap, so the byte-weighted half of the LRU evicts one even
        // though the scope count is under its cap - the size weighting, not just the count, bounds the heap.
        ArtifactStore alpha = store("alpha", "app");
        ArtifactStore beta = store("beta", "app");
        publish(alpha, "maven", "com.alpha:lib", "1.0");
        publish(beta, "maven", "com.beta:lib", "1.0");
        sweep(alpha);
        sweep(beta);

        LuceneSearchQueryProvider provider = new LuceneSearchQueryProvider(
                Duration.ZERO, 64, 1L, Duration.ofHours(1));
        assertThat(hits(provider.over(alpha, "alpha/app"), "alpha")).containsExactly("com.alpha:lib:1.0");
        assertThat(hits(provider.over(beta, "beta/app"), "beta")).containsExactly("com.beta:lib:1.0");

        assertThat(provider.residentScopes())
                .as("a loaded index over the heap-byte cap is evicted even with scope slots to spare")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void an_idle_scope_is_evicted_and_reloads_transparently() throws IOException, InterruptedException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.0");
        sweep(store);

        Duration idle = Duration.ofMillis(20);
        LuceneSearchQueryProvider provider = new LuceneSearchQueryProvider(
                Duration.ZERO, 8, Long.MAX_VALUE, idle);
        assertThat(hits(provider.over(store, "default/app"), "example")).containsExactly("org.example:lib:1.0");
        assertThat(provider.residentScopes()).isEqualTo(1);

        // Let the window lapse and make the later request that sweeps the cache - and keep doing so until the idle
        // scope has actually gone, bounded by sweeps rather than by one sleep chosen to be "long enough". A single
        // sleep would let a stalled JVM assert over a cache that had not been swept at all; each further sweep here
        // only ages the untouched scope, so the wait converges and running out of sweeps fails by name.
        for (int sweep = 0; sweep < SWEEPS; sweep++) {
            Thread.sleep(idle.toMillis() * 2);
            provider.over(store, "default/other");
            if (provider.residentScopes() == 1) {
                break;
            }
        }
        assertThat(provider.residentScopes())
                .as("the scope untouched past the idle window was evicted, leaving only the one just requested")
                .isEqualTo(1);

        assertThat(hits(provider.over(store, "default/app"), "example"))
                .as("a re-queried scope reloads its closed index from the store snapshot, transparently")
                .containsExactly("org.example:lib:1.0");
    }

    private RepositoryContext context(ArtifactStore store) {
        return new RepositoryContext() {

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
                return "test";
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
                return key -> SearchMode.SETTING.equals(key) ? "true" : null;   // the repository asked for an index
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

    /** The coordinates one full page of {@code query} matches, or {@code null} when this repository has no usable
     *  index yet - which the SPI now says with an empty {@link Optional} rather than a {@code null} list.
     *  Every assertion below is about the rows, so the page is unwrapped here once. */
    private static List<String> hits(SearchQuery query, String text) throws IOException {
        return query.search(text, null, SearchQuery.MAX_PAGE)
                .map(page -> page.hits().stream().map(SearchQuery.Hit::display).toList()).orElse(null);
    }


}
