package build.jenesis.repository.health.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.health.store.StoreHealthLedger;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store-backed maintainer-health ledger: a coordinate's health round-trips with every field and is
 * version-independent; a re-scan is a last-writer-wins idempotent upsert that never rolls a fresher record backwards;
 * the repository-wide walk lists every scored coordinate; and the read seam is fail-soft (a missing record is absent,
 * never an exception) - the same safe default a never-scored coordinate produces for the gate.
 */
class StoreHealthLedgerTest {

    private static final Instant FIRST = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-05T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreHealthLedger ledger;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ledger = new StoreHealthLedger(store);
    }

    @Test
    void a_coordinates_health_round_trips_with_every_field() throws IOException {
        ledger.record("Maven", "org.example:lib",
                new Health("github.com/example/lib", 7.2, 8.0, 6.0, Health.NOT_EVALUATED), FIRST);

        Optional<Health> read = ledger.health("Maven", "org.example:lib");
        assertThat(read).isPresent();
        assertThat(read.get().sourceRepository()).isEqualTo("github.com/example/lib");
        assertThat(read.get().overall()).isEqualTo(7.2);
        assertThat(read.get().maintenance()).isEqualTo(8.0);
        assertThat(read.get().review()).isEqualTo(6.0);
        assertThat(read.get().provenance()).isEqualTo(Health.NOT_EVALUATED);
    }

    @Test
    void a_never_scored_coordinate_is_absent_not_an_error() {
        // The gate/read seam: a coordinate with no stored record degrades to empty, the same safe default a live probe
        // that cannot resolve a coordinate produces - never an exception that would fail a gate.
        assertThat(ledger.health("Maven", "org.example:never")).isEmpty();
    }

    @Test
    void a_stale_refresh_never_rolls_a_fresher_record_backwards() throws IOException {
        ledger.record("Maven", "org.example:lib", new Health("repo", 8.0, 8.0, 8.0, 8.0), LATER);
        // A slow sweep finishing AFTER a fresh rescan (an older scored instant) must not overwrite the fresher record.
        ledger.record("Maven", "org.example:lib", new Health("repo", 2.0, 2.0, 2.0, 2.0), FIRST);

        assertThat(ledger.health("Maven", "org.example:lib").orElseThrow().overall())
                .as("last-writer-wins by the scored instant, not by who reaches the store last").isEqualTo(8.0);

        // A genuinely newer refresh does replace it.
        ledger.record("Maven", "org.example:lib", new Health("repo", 3.5, 3.0, 4.0, 3.0), LATER.plusSeconds(1));
        assertThat(ledger.health("Maven", "org.example:lib").orElseThrow().overall()).isEqualTo(3.5);
    }

    @Test
    void the_repository_wide_walk_lists_every_scored_coordinate() throws IOException {
        ledger.record("Maven", "org.example:lib", new Health("r1", 3.0, 3.0, 3.0, 3.0), FIRST);
        ledger.record("npm", "left-pad", new Health("r2", 9.0, 9.0, 9.0, 9.0), FIRST);

        assertThat(ledger.all()).extracting(HealthLedger.Located::coordinate)
                .containsExactlyInAnyOrder("org.example:lib", "left-pad");
    }

    @Test
    void the_provider_is_discovered_via_service_loader() {
        assertThat(HealthLedgerProvider.installed()).isPresent();
        assertThat(HealthLedgerProvider.installed().orElseThrow().over(store)).isInstanceOf(HealthLedger.class);
    }
    @Test
    void the_visitor_walk_pages_each_ecosystem_rather_than_listing_its_whole_coordinate_set() throws IOException {
        for (int index = 0; index < 25; index++) {
            ledger.record("Maven", "org.example:lib" + index,
                    new Health("github.com/example/lib" + index, 5.0, 5.0, 5.0, Health.NOT_EVALUATED), FIRST);
        }

        // all(LedgerVisitor) is the streaming leg the whole-collection ratchet points callers at, and its
        // InheritedBound-ceilinged default is correct - but this override answered it by listing every scored
        // coordinate of an ecosystem into one list before visiting any of them, so the override dropped exactly the
        // protection the default carries. A store that refuses a whole-namespace list under the ledger fails the old
        // body by name and lets the paged one through.
        List<String> visited = new ArrayList<>();
        new StoreHealthLedger(new ListRefusingStore(store)).all(located -> visited.add(located.coordinate()));

        assertThat(visited).as("every scored coordinate is still visited, one row at a time").hasSize(25);
    }

    /** A store that refuses {@code list} under the health ledger's own key spaces while paging exactly as the
     *  delegate does - so a streaming leg that still materialises a level fails by name rather than merely costing
     *  memory. The same fixture shape {@code LevelBoundedStore} uses for the inventory sweeps. */
    private record ListRefusingStore(ArtifactStore delegate) implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.startsWith("health") || prefix.startsWith("meta")) {
                throw new AssertionError("a streaming ledger walk must page '" + prefix + "', never list it");
            }
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new ListRefusingStore(delegate.scope(tenant));
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
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
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
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

}
