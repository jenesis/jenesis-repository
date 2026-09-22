package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.observation.SpiCatalog;
import build.jenesis.repository.settings.ModuleCapability;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-SPI view over the module-capability model behind the SPI catalogue page and {@code /api/admin/spi}: every
 * discovered contract grouped with the installed implementations that provide it, enumerated from the JPMS module
 * graph's {@code provides} declarations rather than a maintained table, and decorated with each implementation's
 * declaring-module enabled state and settings. Two real modules in different JPMS modules on the test path - the
 * retention sweep (gate on) and the download tracker (gate off by default) - exercise the grouping, the namespace
 * filter and the enabled/gate distinction without a fixture, exactly as {@link ModuleCapabilityTest} does for the
 * per-module view they share.
 */
class SpiCatalogTest {

    private static final String RETENTION = "build.jenesis.repository.cleanup.RetentionProvider";
    private static final String MAINTENANCE = "build.jenesis.repository.maintenance.MaintenanceTaskProvider";
    private static final String DOWNLOADS = "build.jenesis.repository.inventory.DownloadTrackerProvider";
    private static final String CONTRIBUTOR = "build.jenesis.repository.settings.SettingsContributor";
    private static final String CLEANUP_MODULE = "build.jenesis.repository.cleanup.task";
    private static final String DOWNLOADS_MODULE = "build.jenesis.repository.downloads";

    @Test
    void every_listed_spi_is_a_product_contract_and_the_listing_is_ordered() {
        // The retention sweep is switched on; the download tracker is left at its (off) default.
        List<SpiCatalog> catalog = ModuleCapability.catalog(
                key -> "scheduled-cleanup".equals(key) ? "true" : null, Set.of());

        assertThat(catalog).as("the graph carries discovered SPIs").isNotEmpty();
        assertThat(catalog).allSatisfy(spi -> assertThat(spi.spi())
                .as("only the product's own SPIs are listed - no framework ServiceLoader noise")
                .startsWith("build.jenesis."));
        assertThat(catalog).extracting(SpiCatalog::spi).as("SPIs are ordered by service name for a stable listing")
                .isSorted();
        assertThat(catalog).allSatisfy(spi -> assertThat(spi.implementations())
                .extracting(SpiCatalog.Implementation::type).as("each SPI's implementations are ordered too")
                .isSorted());
    }

    @Test
    void an_spi_groups_its_installed_implementations_with_the_module_gate_state() {
        Map<String, SpiCatalog> bySpi = index(ModuleCapability.catalog(
                key -> "scheduled-cleanup".equals(key) ? "true" : null, Set.of()));

        // A gated SPI whose module is switched on: the retention engine, provided by the cleanup-task module.
        SpiCatalog.Implementation cleaner = find(bySpi.get(RETENTION), "RepositoryCleanerProvider");
        assertThat(cleaner.type()).isEqualTo("build.jenesis.repository.cleanup.task.RepositoryCleanerProvider");
        assertThat(cleaner.simpleName()).isEqualTo("RepositoryCleanerProvider");
        assertThat(cleaner.module()).isEqualTo(CLEANUP_MODULE);
        assertThat(cleaner.installed()).isTrue();
        assertThat(cleaner.enableKey()).as("the implementation carries its module's enablement gate")
                .isEqualTo("scheduled-cleanup");
        assertThat(cleaner.gated()).isTrue();
        assertThat(cleaner.enabled()).as("its gate is switched on in the effective config").isTrue();

        // The maintenance-task SPI groups many implementations; the same cleanup-task module provides one of them,
        // carrying that module's same gate - one module, two contracts.
        SpiCatalog maintenance = bySpi.get(MAINTENANCE);
        assertThat(maintenance).as("the maintenance-task SPI is discovered").isNotNull();
        assertThat(maintenance.implementations()).as("many maintenance tasks group under the one SPI")
                .hasSizeGreaterThan(1);
        SpiCatalog.Implementation cleanupTask = find(maintenance, "CleanupTaskProvider");
        assertThat(cleanupTask.module()).isEqualTo(CLEANUP_MODULE);
        assertThat(cleanupTask.enabled()).as("it rides the same switched-on module gate").isTrue();

        // A gated SPI, on unless an operator switches it off: the download tracker.
        SpiCatalog.Implementation tracker = find(bySpi.get(DOWNLOADS), "BatchingDownloadTrackerProvider");
        assertThat(tracker.module()).isEqualTo(DOWNLOADS_MODULE);
        assertThat(tracker.enableKey()).isEqualTo("track-downloads");
        assertThat(tracker.enabled()).as("its gate is on unless switched off").isTrue();
    }

    @Test
    void an_spi_with_several_installed_implementations_groups_them_under_one_entry() {
        Map<String, SpiCatalog> bySpi = index(ModuleCapability.catalog(_ -> null, Set.of()));

        // SettingsContributor is provided by many modules; the catalogue groups them under the one contract.
        SpiCatalog contributors = bySpi.get(CONTRIBUTOR);
        assertThat(contributors).as("the settings-contributor SPI is discovered").isNotNull();
        assertThat(contributors.implementations()).as("its many implementations group under one SPI entry")
                .hasSizeGreaterThan(1);
        assertThat(contributors.implementations()).extracting(SpiCatalog.Implementation::module)
                .as("including the two test-path contributors, attributed to their own modules")
                .contains(CLEANUP_MODULE, DOWNLOADS_MODULE);
    }

    /** The one implementation of {@code spi} whose provider type ends with {@code typeSuffix} - robust to however many
     *  other implementations of the same SPI the module path carries. */
    private static SpiCatalog.Implementation find(SpiCatalog spi, String typeSuffix) {
        assertThat(spi).as("the SPI is discovered from the module graph").isNotNull();
        return spi.implementations().stream()
                .filter(implementation -> implementation.type().endsWith(typeSuffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError(typeSuffix + " is not an implementation of " + spi.spi()));
    }

    private static Map<String, SpiCatalog> index(List<SpiCatalog> catalog) {
        Map<String, SpiCatalog> bySpi = new HashMap<>();
        for (SpiCatalog spi : catalog) {
            bySpi.put(spi.spi(), spi);
        }
        return bySpi;
    }
}
