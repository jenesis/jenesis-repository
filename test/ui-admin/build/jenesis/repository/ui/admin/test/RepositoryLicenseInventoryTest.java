package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.inventory.LicenseReport;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The console's licence inventory over {@link RepositoryBrowse}: it reads the stored report of the signed-in tenant's
 * repository - not counted until a count is asked for - and the count it starts runs in the background over that
 * repository, with no full-text index anywhere in the composition, and lands as counts of versions.
 */
public class RepositoryLicenseInventoryTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private ArtifactStore repository() {
        return store.scope("acme").scope("releases");
    }

    private RepositoryBrowse browse() {
        return new RepositoryBrowse(store, () -> "acme", ObservationRegistry.NOOP, Optional.empty());
    }

    @Test
    void a_count_started_from_the_console_lands_in_the_report_the_screen_reads() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repository());
        inventory.record("Maven", "org.acme:lib", "1.0", NOW);
        inventory.record("Maven", "org.acme:lib", "2.0", NOW);
        new LicenseInventory(repository()).record("Maven", "org.acme:lib", "1.0",
                List.of(new LicenseInventory.Declared("Apache License, Version 2.0", null)));
        new LicenseInventory(repository()).record("Maven", "org.acme:lib", "2.0",
                List.of(new LicenseInventory.Declared("Apache License, Version 2.0", null)));

        assertThat(browse().licenses("releases").state()).isEqualTo(LicenseReport.State.NOT_COUNTED);

        assertThat(browse().countLicenses("releases")).isTrue();
        assertThat(StoredReport.awaitSettled(repository(), LicenseReport.NAME, Duration.ofMinutes(1))).isPresent();

        LicenseReport.Inventory counted = browse().licenses("releases");
        assertThat(counted.state()).isEqualTo(LicenseReport.State.DONE);
        assertThat(counted.versions()).isEqualTo(2);
        assertThat(counted.licenses()).extracting(LicenseReport.Count::value, LicenseReport.Count::versions)
                .as("two versions of one coordinate are two versions").containsExactly(tuple("Apache-2.0", 2L));
        assertThat(LicenseReport.read(store.scope("other").scope("releases")).state())
                .as("another tenant's repository of the same name was not counted")
                .isEqualTo(LicenseReport.State.NOT_COUNTED);
    }
}
