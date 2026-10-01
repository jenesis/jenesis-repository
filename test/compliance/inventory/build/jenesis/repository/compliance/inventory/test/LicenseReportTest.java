package build.jenesis.repository.compliance.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.inventory.LicenseReport;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The licence inventory's count and its stored report, over a real filesystem store: every version is counted once
 * towards each distinct category and SPDX id it declares, a licence nothing identifies counts as unknown, a version
 * with no recorded licences is re-derived from its metadata, and the report reads back as not counted, running,
 * done or failed - including a run whose node went away, which must not read as running for ever.
 */
class LicenseReportTest {

    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    /** Long enough for a background count of a handful of versions on a saturated machine; a wait, not a bound on
     *  the product. */
    private static final Duration PATIENCE = Duration.ofMinutes(1);

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    /** A published version, with the licences the gate recorded for it - none recorded when {@code names} is
     *  {@code null}, which is a version published before the gate recorded any. */
    private static void publish(ArtifactStore store, String ecosystem, String coordinate, String version,
                                String... names) throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream(new byte[16]));
        new Publication(store).link("/" + ecosystem + "/" + coordinate + "/" + version + "/artifact", hash);
        new StoreRepositoryInventory(store).record(ecosystem, coordinate, version, false, NOW);
        if (names != null) {
            List<LicenseInventory.Declared> declared = new ArrayList<>();
            for (String name : names) {
                declared.add(new LicenseInventory.Declared(name, null));
            }
            new LicenseInventory(store).record(ecosystem, coordinate, version, declared);
        }
    }

    @Test
    void every_version_counts_once_towards_each_category_and_spdx_id_it_declares() throws IOException {
        ArtifactStore store = store();
        publish(store, "maven", "org.example:lib", "1.0", "MIT License");
        publish(store, "maven", "org.example:lib", "2.0", "MIT License", "MIT");      // one licence, said twice
        publish(store, "maven", "org.example:dual", "1.0", "MIT License", "Apache License, Version 2.0");
        publish(store, "maven", "org.example:gpl", "1.0", "GNU General Public License, version 3");

        LicenseReport.Inventory inventory = written(store);

        assertThat(inventory.state()).isEqualTo(LicenseReport.State.DONE);
        assertThat(inventory.versions()).as("the unit is a version, not a coordinate").isEqualTo(4);
        assertThat(inventory.categories()).extracting(LicenseReport.Count::value, LicenseReport.Count::versions)
                .as("a version declaring two permissive licences is one permissive version, most versions first")
                .containsExactly(tuple("permissive", 3L), tuple("strong-copyleft", 1L));
        assertThat(inventory.licenses()).extracting(LicenseReport.Count::value, LicenseReport.Count::versions)
                .as("and a licence it names twice counts once")
                .containsExactly(tuple("MIT", 3L), tuple("Apache-2.0", 1L), tuple("GPL", 1L));
        assertThat(inventory.truncated()).isFalse();
        assertThat(inventory.finishedAt()).isNotNull();
    }

    @Test
    void a_licence_nothing_identifies_and_a_version_that_declares_none_count_as_unknown() throws IOException {
        ArtifactStore store = store();
        publish(store, "maven", "org.example:custom", "1.0", "Acme Internal Terms");
        publish(store, "maven", "org.example:bare", "1.0");                      // inspected, nothing declared
        publish(store, "maven", "org.example:mit", "1.0", "MIT License");

        LicenseReport.Inventory inventory = written(store);

        assertThat(inventory.versions()).isEqualTo(3);
        assertThat(inventory.categories()).extracting(LicenseReport.Count::value, LicenseReport.Count::versions)
                .containsExactly(tuple("unknown", 2L), tuple("permissive", 1L));
        assertThat(inventory.licenses()).extracting(LicenseReport.Count::value)
                .as("an unidentified licence has no SPDX id to be counted under").containsExactly("MIT");
    }

    @Test
    void a_version_with_no_recorded_licences_is_counted_from_its_metadata() throws IOException {
        ArtifactStore store = store();
        publish(store, "fake", "example-lib", "2.0", (String[]) null);   // the fake inspector derives Apache

        LicenseReport.Inventory inventory = written(store);

        assertThat(inventory.licenses()).extracting(LicenseReport.Count::value, LicenseReport.Count::versions)
                .containsExactly(tuple("Apache-2.0", 1L));
    }

    @Test
    void a_repository_never_counted_says_so_and_an_empty_one_counts_nothing() throws IOException {
        ArtifactStore store = store();
        assertThat(LicenseReport.read(store).state()).isEqualTo(LicenseReport.State.NOT_COUNTED);

        LicenseReport.Inventory empty = written(store);
        assertThat(empty.state()).as("counted, and there was nothing to count").isEqualTo(LicenseReport.State.DONE);
        assertThat(empty.versions()).isZero();
        assertThat(empty.categories()).isEmpty();
    }

    @Test
    void a_count_started_in_the_background_reads_as_running_until_it_lands() throws Exception {
        ArtifactStore store = store();
        publish(store, "maven", "org.example:lib", "1.0", "MIT License");
        CountDownLatch release = new CountDownLatch(1);
        // The report's own name, held by a run that waits: what LicenseReport.start does, with a pass this test
        // controls, so the running state is observed rather than raced.
        assertThat(StoredReport.compute(store, LicenseReport.NAME, () -> {
            awaitUninterruptibly(release);
            return LicenseReport.count(store);
        })).isTrue();
        try {
            LicenseReport.Inventory running = LicenseReport.read(store);
            assertThat(running.state()).isEqualTo(LicenseReport.State.RUNNING);
            assertThat(running.startedAt()).isNotNull();
            assertThat(running.asOf()).isEqualTo(running.startedAt());
            assertThat(LicenseReport.start(store)).as("a second count is declined while one runs").isFalse();
        } finally {
            release.countDown();
        }
        assertThat(StoredReport.awaitSettled(store, LicenseReport.NAME, PATIENCE)).isPresent();
        LicenseReport.Inventory done = LicenseReport.read(store);
        assertThat(done.state()).isEqualTo(LicenseReport.State.DONE);
        assertThat(done.versions()).isEqualTo(1);
        assertThat(done.asOf()).isEqualTo(done.finishedAt());

        assertThat(LicenseReport.start(store)).as("a finished count frees the report for the next").isTrue();
        assertThat(StoredReport.awaitSettled(store, LicenseReport.NAME, PATIENCE)).isPresent();
        assertThat(LicenseReport.read(store).state()).isEqualTo(LicenseReport.State.DONE);
    }

    @Test
    void a_count_that_fails_reads_as_failed_with_its_reason() throws IOException {
        ArtifactStore store = store();
        assertThat(StoredReport.compute(store, LicenseReport.NAME, () -> {
            throw new IOException("the store went away");
        })).isTrue();
        assertThat(StoredReport.awaitSettled(store, LicenseReport.NAME, PATIENCE)).isPresent();

        LicenseReport.Inventory failed = LicenseReport.read(store);
        assertThat(failed.state()).isEqualTo(LicenseReport.State.FAILED);
        assertThat(failed.failure()).contains("the store went away");
    }

    @Test
    void a_count_whose_node_went_away_reads_as_failed_rather_than_running_for_ever() throws Exception {
        ArtifactStore store = store();
        CountDownLatch release = new CountDownLatch(1);
        assertThat(StoredReport.compute(store, LicenseReport.NAME, () -> {
            awaitUninterruptibly(release);
            return LicenseReport.count(store);
        })).isTrue();
        try {
            // The run's lease gone while the report still says running: what a node that died mid-count leaves once
            // its lease has lapsed.
            store.delete(Lease.objectKey("report-" + LicenseReport.NAME));
            LicenseReport.Inventory abandoned = LicenseReport.read(store);
            assertThat(abandoned.state()).isEqualTo(LicenseReport.State.FAILED);
            assertThat(abandoned.failure()).contains("went away");
        } finally {
            release.countDown();
        }
        assertThat(StoredReport.awaitSettled(store, LicenseReport.NAME, PATIENCE)).isPresent();
    }

    @Test
    void a_report_with_more_rows_than_it_kept_reads_as_cut_short() throws IOException {
        ArtifactStore store = store();
        List<String> rows = new ArrayList<>();
        rows.add("versions\t\t500");
        rows.add("category\tpermissive\t500");
        for (int i = 0; rows.size() < StoredReport.SAMPLE; i++) {
            rows.add("license\tLicense-" + i + "\t2");
        }
        StoredReport.write(store, LicenseReport.NAME, NOW, NOW, new StoredReport.Rows(StoredReport.SAMPLE + 50,
                rows));

        LicenseReport.Inventory inventory = LicenseReport.read(store);

        assertThat(inventory.truncated()).isTrue();
        assertThat(inventory.rows()).isEqualTo(StoredReport.SAMPLE + 50);
        assertThat(inventory.licenses()).hasSize(StoredReport.SAMPLE - 2);
        assertThat(inventory.versions()).isEqualTo(500);
    }

    /** Count now and store the report as a finished count does, then read it back the way every surface does. */
    private static LicenseReport.Inventory written(ArtifactStore store) throws IOException {
        Instant started = Instant.now();
        StoredReport.write(store, LicenseReport.NAME, started, Instant.now(), LicenseReport.count(store));
        return LicenseReport.read(store);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the test never released the count");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
