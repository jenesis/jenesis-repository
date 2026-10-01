package build.jenesis.repository.compliance.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.inventory.LicenseReport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored report keeps its last finished result while a new run is under way and after one fails, so a screen
 * shows the last numbers it had rather than nothing until the next run lands; a finished run replaces them. The
 * licence inventory reads the same, as the last finished count beside the running one.
 */
class StoredReportPreviousTest {

    private static final Duration PATIENCE = Duration.ofMinutes(1);

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    @Test
    void a_running_and_a_failed_run_carry_the_last_finished_result_and_a_finished_run_replaces_it()
            throws IOException, InterruptedException {
        ArtifactStore store = store();
        StoredReport.write(store, "counted", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"), StoredReport.Rows.of(List.of("first", "second")));

        CountDownLatch release = new CountDownLatch(1);
        assertThat(StoredReport.compute(store, "counted", () -> {
            await(release);
            return StoredReport.Rows.of(List.of("third"));
        })).isTrue();
        StoredReport.Report running = StoredReport.read(store, "counted").orElseThrow();
        assertThat(running.running()).isTrue();
        assertThat(running.rows()).as("a running report has no rows of its own").isEmpty();
        assertThat(running.previous()).isNotNull();
        assertThat(running.previous().rows()).containsExactly("first", "second");
        assertThat(running.previous().finishedAt()).isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));
        assertThat(running.lastFinished()).contains(running.previous());

        release.countDown();
        StoredReport.Report done = StoredReport.awaitSettled(store, "counted", PATIENCE).orElseThrow();
        assertThat(done.rows()).containsExactly("third");
        assertThat(done.previous()).as("a finished report carries none").isNull();

        assertThat(StoredReport.compute(store, "counted", () -> {
            throw new IOException("the store went away");
        })).isTrue();
        StoredReport.Report failed = StoredReport.awaitSettled(store, "counted", PATIENCE).orElseThrow();
        assertThat(failed.status()).isEqualTo(StoredReport.Status.FAILED);
        assertThat(failed.previous().rows()).as("a failure leaves the last result standing").containsExactly("third");
    }

    @Test
    void the_licence_inventory_reads_the_last_finished_count_while_a_new_one_runs() throws IOException {
        ArtifactStore store = store();
        LicenseReport.start(store);
        StoredReport.awaitSettled(store, LicenseReport.NAME, PATIENCE).orElseThrow();
        LicenseReport.Inventory first = LicenseReport.read(store);
        assertThat(first.state()).isEqualTo(LicenseReport.State.DONE);
        assertThat(first.shown()).isSameAs(first);

        CountDownLatch release = new CountDownLatch(1);
        StoredReport.compute(store, LicenseReport.NAME, () -> {
            await(release);
            return LicenseReport.count(store);
        });
        try {
            LicenseReport.Inventory running = LicenseReport.read(store);
            assertThat(running.state()).isEqualTo(LicenseReport.State.RUNNING);
            assertThat(running.shown()).as("the screen shows the last finished count").isNotNull();
            assertThat(running.shown().finishedAt()).isEqualTo(first.finishedAt());
        } finally {
            release.countDown();
            StoredReport.awaitSettled(store, LicenseReport.NAME, PATIENCE);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("the test never released the run");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
