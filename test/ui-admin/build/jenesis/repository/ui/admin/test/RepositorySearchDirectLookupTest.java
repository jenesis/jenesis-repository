package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.search.LicenseFacet;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * When the search index answers, the console must resolve each hit's location by a bounded direct lookup - not by
 * enumerating the whole {@code published/} tree per request just to filter it down to the indexed hits (a full-store
 * walk on a read path). This drives {@link RepositoryBrowse#search} with a fake index that returns one hit, over a
 * store that <em>refuses to list an ecosystem folder</em> ({@code published/<eco>}, the coordinate-enumeration step a
 * full walk needs and a direct lookup never takes). The search still resolves the hit, proving it took the direct
 * path rather than a repository walk.
 */
public class RepositorySearchDirectLookupTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore backing;

    @BeforeEach
    void setUp() throws IOException {
        backing = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(backing.scope("acme").scope("releases"));
        // One hit coordinate the fake index returns, plus another coordinate a full walk would enumerate. Both sit
        // under published/maven/ - the ecosystem folder the refusing store guards.
        inventory.record("maven", "org.acme:lib", "1.0", NOW);
        inventory.record("maven", "org.other:tool", "9.0", NOW);
    }

    @Test
    void an_indexed_hit_is_resolved_without_walking_the_published_tree() throws IOException {
        ListRefusingStore refusingRoot = new ListRefusingStore(backing);
        RepositoryBrowse admin = new RepositoryBrowse(refusingRoot, () -> "acme",
                ObservationRegistry.NOOP, Optional.of(new FakeIndex(List.of("org.acme:lib:1.0"))));

        List<RepositoryBrowse.SearchResult> results = new ArrayList<>();
        assertThatCode(() -> results.addAll(admin.search("releases", "lib").results()))
                .as("resolving the indexed hit never enumerates all coordinates under an ecosystem")
                .doesNotThrowAnyException();

        assertThat(results).singleElement().satisfies(hit -> {
            assertThat(hit.coordinate()).isEqualTo("org.acme:lib");
            assertThat(hit.version()).isEqualTo("1.0");
            assertThat(hit.ecosystem()).isEqualTo("maven");
        });
    }

    /** The fake installed index: returns a fixed hit set so {@link RepositoryBrowse#search} takes its indexed branch. */
    private record FakeIndex(List<String> hits) implements SearchQueryProvider, SearchQuery {
        @Override
        public SearchQuery over(ArtifactStore store, String scope) {
            return this;
        }

        @Override
        public Optional<Hits> search(String query, String cursor, int limit) {
            return Optional.of(Hits.last(hits));
        }

        @Override
        public Optional<List<LicenseFacet>> licenses() {
            return Optional.empty();
        }
    }

    /** A store that refuses to list or page an ecosystem folder ({@code meta/<eco>}) - the coordinate-enumeration a
     *  full repository walk performs. A direct hit lookup lists only {@code meta} (the ecosystems) and
     *  {@code meta/<eco>/<coordinate>} (one coordinate's versions), never the ecosystem folder itself, so it
     *  passes; the old full-walk search would throw here. Reads and writes delegate untouched; {@link #scope}
     *  propagates the guard. A test double, never a backend. */
    private static final class ListRefusingStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private static final Pattern ECOSYSTEM_FOLDER = Pattern.compile("meta/[^/]+");

        private final ArtifactStore delegate;

        private ListRefusingStore(ArtifactStore delegate) {
            this.delegate = delegate;
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
        if (ECOSYSTEM_FOLDER.matcher(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                .matches()) {
            throw new AssertionError("full coordinate walk: paged every coordinate under " + prefix);
        }
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
