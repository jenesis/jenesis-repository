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
 * The /§5.4 cutover: with a metadata store installed, {@link StoreHealthLedger} records into the {@code health}
 * section of the consolidated per-coordinate document ({@code meta/<eco>/<enc(coord)>/@coordinate}) rather than a
 * standalone {@code health/} sidecar - a recorded coordinate lands in and reads back from the section, never the
 * retired sidecar. There is no fall-through between the two layouts: a coordinate with no section is unscored here,
 * and a {@code health/} object left behind by a deployment that predates the persistence module is neither read nor
 * swept. With no metadata module installed the sidecar subtree is the whole ledger, streamed without buffering.
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
        metadata = MetadataProvider.installed().orElseThrow().over(store);
        ledger = new StoreHealthLedger(store);
    }

    @Test
    void a_recorded_health_lands_in_the_coordinate_documents_health_section_not_a_sidecar() throws IOException {
        ledger.record("Maven", "org.example:lib",
                new Health("github.com/example/lib", 7.2, 8.0, 6.0, Health.NOT_EVALUATED), FIRST);

        assertThat(HealthSection.stored(metadata.coordinateSection("Maven", "org.example:lib", HealthSection.TAG)))
                .as("the record lands in the @coordinate document's health section").isPresent()
                .get().satisfies(stored -> {
                    assertThat(stored.health().sourceRepository()).isEqualTo("github.com/example/lib");
                    assertThat(stored.health().overall()).isEqualTo(7.2);
                    assertThat(stored.scannedAt()).isEqualTo(FIRST);
                });
        assertThat(store.readVersioned(HealthLedger.key("Maven", "org.example:lib")))
                .as("the cutover writes the section, never the retired health/ sidecar").isEmpty();
        assertThat(ledger.health("Maven", "org.example:lib").orElseThrow().overall())
                .as("the read seam serves the section").isEqualTo(7.2);
    }

    @Test
    void a_coordinate_with_no_section_is_unscored_and_its_leftover_sidecar_is_neither_read_nor_swept()
            throws IOException {
        // A health/ object written by a deployment that predates the persistence module. With the module installed the
        // section is the whole answer, so this reads as unscored - there is no fall-through to an older key. And the
        // read leaves it exactly where it is: removing a deployment's data is the operator's explicit purge, never a
        // side effect of the layout it is no longer reachable under.
        store.writeVersioned(HealthLedger.key("Maven", "org.example:leftover"),
                sidecar("github.com/example/leftover", 4.5, 5.0, 4.0, 3.0, FIRST), null);

        assertThat(metadata.coordinateSection("Maven", "org.example:leftover", HealthSection.TAG))
                .as("no section was written for it").isEmpty();
        assertThat(ledger.health("Maven", "org.example:leftover"))
                .as("an absent section means unscored - the sidecar is not consulted").isEmpty();
        assertThat(store.readVersioned(HealthLedger.key("Maven", "org.example:leftover")))
                .as("and the read never removes it").isPresent();
    }

    @Test
    void the_walk_is_the_section_plane_alone_and_a_leftover_sidecar_never_shadows_or_joins_it() throws IOException {
        ledger.record("Maven", "org.section:one", new Health("r1", 3.0, 3.0, 3.0, 3.0), FIRST);   // -> section
        store.writeVersioned(HealthLedger.key("npm", "left-pad"),
                sidecar("r2", 9.0, 9.0, 9.0, 9.0, FIRST), null);                                  // -> sidecar only
        // A coordinate present in BOTH its section and a leftover sidecar: the section is what the walk reports, and
        // the stale sidecar contributes nothing.
        store.writeVersioned(HealthLedger.key("Maven", "org.section:one"),
                sidecar("stale", 1.0, 1.0, 1.0, 1.0, FIRST), null);

        assertThat(ledger.all()).extracting(HealthLedger.Located::coordinate)
                .as("the walk is the section plane: a sidecar-only coordinate is not in the ledger")
                .containsExactly("org.section:one");
        assertThat(ledger.all()).singleElement()
                .satisfies(located -> assertThat(located.health().overall())
                        .as("the section answers, never the leftover sidecar beside it").isEqualTo(3.0));
    }

    @Test
    void with_no_metadata_module_the_walk_streams_every_sidecar_without_buffering_the_fleet() throws IOException {
        // The graceful-absence deployment (no metadata module): the ledger lives entirely in health/ sidecars and the
        // walk is a pure streaming sidecar scan - it must deliver every recorded coordinate without buffering the
        // fleet the rank-index rebuild rides on. A null metadata store pins that no-metadata path where the module
        // otherwise installs a provider.
        StoreHealthLedger sidecarOnly = new StoreHealthLedger(store, null);
        for (int i = 0; i < 50; i++) {
            sidecarOnly.record("Maven", "org.acme:lib" + i,
                    new Health("r" + i, i / 10.0, 5.0, 5.0, 5.0), FIRST);
        }

        List<String> walked = new ArrayList<>();
        sidecarOnly.all(located -> walked.add(located.coordinate()));

        assertThat(walked).as("every sidecar-homed coordinate is delivered, none dropped").hasSize(50);
        assertThat(walked).contains("org.acme:lib0", "org.acme:lib49");
        assertThat(sidecarOnly.all()).as("the buffered list mirrors the streamed walk").hasSize(50);
    }

    /** The {@code health/} sidecar's on-disk JSON shape, exactly as {@code StoreHealthLedger} writes it on the
     *  graceful-absence path. */
    private static byte[] sidecar(String sourceRepository, double overall, double maintenance, double review,
                                  double provenance, Instant scannedAt) {
        return ("{\"sourceRepository\":\"" + sourceRepository + "\",\"overall\":" + overall
                + ",\"maintenance\":" + maintenance + ",\"review\":" + review + ",\"provenance\":" + provenance
                + ",\"scannedAt\":\"" + scannedAt + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}
