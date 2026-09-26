package build.jenesis.repository.health.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.store.HealthSection;
import build.jenesis.repository.health.store.StoreHealthLedger;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StoreHealthLedger} records into the {@code health} section of the consolidated per-coordinate document
 * ({@code meta/<eco>/<enc(coord)>/@coordinate}): a recorded coordinate lands in and reads back from the section, and
 * the ledger's walk streams every scored coordinate without buffering the fleet.
 */
class StoreHealthLedgerSectionTest {

    private static final Instant FIRST = Instant.parse("2026-07-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;
    private StoreHealthLedger ledger;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        metadata = MetadataProvider.installed().over(store);
        ledger = new StoreHealthLedger(store);
    }

    @Test
    void a_recorded_health_lands_in_the_coordinate_documents_health_section() throws IOException {
        ledger.record("Maven", "org.example:lib",
                new Health("github.com/example/lib", 7.2, 8.0, 6.0, Health.NOT_EVALUATED), FIRST);

        assertThat(HealthSection.stored(metadata.coordinateSection("Maven", "org.example:lib", HealthSection.TAG)))
                .as("the record lands in the @coordinate document's health section").isPresent()
                .get().satisfies(stored -> {
                    assertThat(stored.health().sourceRepository()).isEqualTo("github.com/example/lib");
                    assertThat(stored.health().overall()).isEqualTo(7.2);
                    assertThat(stored.scannedAt()).isEqualTo(FIRST);
                });
        assertThat(ledger.health("Maven", "org.example:lib").orElseThrow().overall())
                .as("the read seam serves the section").isEqualTo(7.2);
    }

    @Test
    void the_walk_streams_every_scored_coordinate() throws IOException {
        // The walk is a pure streaming scan of the coordinate documents - it must deliver every recorded coordinate
        // without buffering the fleet the rank-index rebuild rides on.
        for (int i = 0; i < 50; i++) {
            ledger.record("Maven", "org.acme:lib" + i, new Health("r" + i, i / 10.0, 5.0, 5.0, 5.0), FIRST);
        }

        List<String> walked = new ArrayList<>();
        ledger.all(located -> walked.add(located.coordinate()));

        assertThat(walked).as("every scored coordinate is delivered, none dropped").hasSize(50);
        assertThat(walked).contains("org.acme:lib0", "org.acme:lib49");
        assertThat(ledger.all()).as("the buffered list mirrors the streamed walk").hasSize(50);
    }
}
