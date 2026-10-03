package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * When the full-text index answers, the console places each hit by a bounded direct lookup - never by enumerating the
 * whole {@code meta/} tree per request just to filter it down to the indexed hits (a full-store walk on a read path).
 * This drives {@link RepositoryBrowse#search} with a fake index that returns one hit, over a store that <em>refuses to
 * list an ecosystem folder whole</em> ({@code meta/<eco>}, the coordinate enumeration a full walk needs) while it still
 * pages one bounded window of it, as the name lookup leading the page does. The search still places the hit, proving
 * it took the direct path rather than a repository walk.
 */
public class RepositorySearchDirectLookupTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore backing;

    @BeforeEach
    void setUp() throws IOException {
        backing = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(backing.scope("acme").scope("releases"));
        // One hit coordinate the fake index returns, plus another coordinate a full walk would enumerate. Both sit
        // under meta/maven/ - the ecosystem folder the refusing store guards.
        inventory.record("maven", "org.acme:lib", "1.0", NOW);
        inventory.record("maven", "org.other:tool", "9.0", NOW);
    }

    @Test
    void an_indexed_hit_is_resolved_without_walking_the_published_tree() throws IOException {
        ListRefusingStore refusingRoot = new ListRefusingStore(backing);
        RepositoryBrowse admin = new RepositoryBrowse(refusingRoot, () -> "acme",
                ObservationRegistry.NOOP,
                Optional.of(new FakeIndex(List.of(SearchQuery.Hit.coordinate("maven", "org.acme:lib", "1.0")))));

        List<RepositoryBrowse.SearchResult> results = new ArrayList<>();
        assertThatCode(() -> results.addAll(admin.search("releases",
                        key -> SearchMode.SETTING.equals(key) ? "true" : null, "lib", null).results()))
                .as("resolving the indexed hit never enumerates all coordinates under an ecosystem")
                .doesNotThrowAnyException();

        assertThat(results).singleElement().satisfies(hit -> {
            assertThat(hit.coordinate()).isEqualTo("org.acme:lib");
            assertThat(hit.version()).isEqualTo("1.0");
            assertThat(hit.ecosystem()).isEqualTo("maven");
        });
    }

    /** The fake installed index: returns a fixed hit set so {@link RepositoryBrowse#search} takes its indexed branch. */
    private record FakeIndex(List<SearchQuery.Hit> hits) implements SearchQueryProvider, SearchQuery {
        @Override
        public SearchQuery over(ArtifactStore store, String scope) {
            return this;
        }

        @Override
        public Optional<Hits> search(String query, String cursor, int limit) {
            return Optional.of(Hits.last(hits));
        }
    }

    /** A store that refuses to list or scan an ecosystem folder ({@code meta/<eco>}) whole - the coordinate
     *  enumeration a full repository walk performs - while it pages one bounded window of it through to the
     *  delegate. A direct hit lookup reads only point keys and bounded pages, so it passes; a full-walk search would
     *  throw here. Reads and writes delegate untouched; {@link #scope} propagates the guard. A test double, never a
     *  backend. */
    private static final class ListRefusingStore extends ForwardingArtifactStore {
        private static final Pattern ECOSYSTEM_FOLDER = Pattern.compile("meta/[^/]+");

        private ListRefusingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new ListRefusingStore(delegate.scope(tenant));
        }

        @Override
        public List<String> list(String prefix) {
            if (ECOSYSTEM_FOLDER.matcher(prefix).matches()) {
                throw new AssertionError("full coordinate walk: enumerated every coordinate under " + prefix);
            }
            return delegate.list(prefix);
        }

        @Override
        public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
            if (ECOSYSTEM_FOLDER.matcher(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                    .matches()) {
                throw new AssertionError("full coordinate walk: paged every coordinate under " + prefix);
            }
            return delegate.scan(prefix, startAfter, limit, consumer);
        }
    }
}
