package build.jenesis.repository.health.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.store.HealthScanTask;
import build.jenesis.repository.health.store.StoreHealthLedger;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled maintainer-health sweep: it walks a repository's published coordinates, persists what the live source
 * scores into the durable ledger, and stamps the sweep instant - so the read surfaces and the gate serve a stored
 * answer. A coordinate the source scores nothing is left unrecorded (unknown, not healthy), so a later read resolves it
 * to the same safe default rather than a fabricated clean score.
 */
class HealthScanTaskTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void the_sweep_populates_the_ledger_and_stamps_its_freshness() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.record("Maven", "org.example:healthy", "1.0", NOW);
        inventory.record("Maven", "org.example:healthy", "2.0", NOW);   // same coordinate, two versions - probed once
        inventory.record("Maven", "org.example:unscored", "1.0", NOW);

        HealthSource source = HealthSource.of(Map.of(
                "org.example:healthy", new Health("github.com/example/healthy", 6.5, 7.0, 6.0, Health.NOT_EVALUATED)));
        new HealthScanTask(Duration.ZERO, source).repository(context(NOW));

        HealthLedger ledger = new StoreHealthLedger(store);
        assertThat(ledger.health("Maven", "org.example:healthy")).isPresent();
        assertThat(ledger.health("Maven", "org.example:healthy").orElseThrow().overall()).isEqualTo(6.5);
        assertThat(ledger.health("Maven", "org.example:unscored"))
                .as("a coordinate the source scores nothing is left unrecorded (unknown, not healthy)").isEmpty();
        assertThat(HealthLedger.scanned(store).read()).as("a completed pass stamps the ledger's freshness").contains(NOW);
    }

    @Test
    void a_repository_never_swept_has_no_stamp() throws IOException {
        assertThat(HealthLedger.scanned(store).read()).as("never scanned reads as such, not as healthy").isEmpty();
    }

    @Test
    void the_passes_between_full_ones_probe_only_the_coordinates_published_since() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.record("Maven", "org.old:a", "1.0", NOW.minus(Duration.ofDays(2)));
        inventory.record("Maven", "org.old:b", "1.0", NOW.minus(Duration.ofDays(2)));
        List<String> probed = new ArrayList<>();
        HealthSource counting = new HealthSource() {
            @Override
            public Optional<Health> health(String ecosystem, String coordinate) {
                probed.add(coordinate);
                return Optional.of(new Health("github.com/example/" + coordinate, 6.5, 7.0, 6.0, Health.NOT_EVALUATED));
            }

            @Override
            public Freshness freshness() {
                return HealthSource.of(Map.of()).freshness();
            }
        };
        HealthScanTask task = new HealthScanTask(Duration.ZERO, counting);

        task.repository(context(NOW));
        assertThat(probed).as("the first pass is full: nothing has ever been probed")
                .containsExactlyInAnyOrder("org.old:a", "org.old:b");
        assertThat(HealthLedger.scanned(store).read()).contains(NOW);

        probed.clear();
        inventory.record("Maven", "org.new:c", "1.0", NOW.plus(Duration.ofMinutes(30)));
        task.repository(context(NOW.plus(Duration.ofHours(1))));
        assertThat(probed).as("the second pass is incremental: only what was published since the full one")
                .containsExactly("org.new:c");
        assertThat(HealthLedger.scanned(store).read())
                .as("an incremental pass cannot claim the scores are as of now; the stamp is the full pass's")
                .contains(NOW);
    }

    private RepositoryContext context(Instant now) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "acme";
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
                return key -> null;
            }

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
