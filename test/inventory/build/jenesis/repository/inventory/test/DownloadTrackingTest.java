package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.FaultInjectingStore.Op;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Download tracking, the signal the {@code not-downloaded-for} retention criterion ages by. The store-backed
 * last-download marker persists and reads back across inventory instances, its compare-and-set write retries past an
 * injected version conflict rather than silently dropping the write (so a concurrent tracker write is never lost), and
 * the {@code NONE} tracker plus the provider-absent {@code resolve} degrade cleanly - the graceful-absence path a
 * deployment without the downloads module rides.
 */
class DownloadTrackingTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore backend;

    @BeforeEach
    void setUp() {
        backend = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    @Test
    void a_last_download_marker_persists_store_backed_and_reads_back() throws IOException {
        new StoreRepositoryInventory(backend).recordDownload(ECO, "com.example:lib", "1.0.0", NOW);

        // A fresh inventory instance reads the marker back - it is durable store state, not in-memory.
        assertThat(new StoreRepositoryInventory(backend).lastDownloaded(ECO, "com.example:lib", "1.0.0"))
                .contains(NOW);
        assertThat(new StoreRepositoryInventory(backend).lastDownloaded(ECO, "com.example:lib", "absent"))
                .as("an un-downloaded version has no marker").isEmpty();
    }

    @Test
    void a_later_download_advances_the_marker() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(backend);
        inventory.recordDownload(ECO, "com.example:lib", "1.0.0", NOW);
        Instant later = NOW.plus(Duration.ofDays(2));
        inventory.recordDownload(ECO, "com.example:lib", "1.0.0", later);

        assertThat(inventory.lastDownloaded(ECO, "com.example:lib", "1.0.0"))
                .as("the newer download overwrites the marker").contains(later);
    }

    @Test
    void the_marker_write_retries_past_a_version_conflict_rather_than_dropping() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        // The fact lives in the version's document now; the conflict is injected where the write goes.
        store.conflictNext(FaultInjectingStore.keyPrefix("meta/"));

        new StoreRepositoryInventory(store).recordDownload(ECO, "com.example:lib", "1.0.0", NOW);

        assertThat(store.calls(Op.WRITE_VERSIONED))
                .as("the compare-and-set re-read the token and retried past the injected conflict")
                .isGreaterThanOrEqualTo(2);
        assertThat(new StoreRepositoryInventory(backend).lastDownloaded(ECO, "com.example:lib", "1.0.0"))
                .as("the write landed, not silently lost").contains(NOW);
    }

    @Test
    void the_none_tracker_records_nothing_and_reports_off() {
        DownloadTracker none = DownloadTracker.NONE;

        assertThat(none.enabled()).isFalse();
        assertThat(none.alive()).isFalse();
        assertThat(none.dropped()).isZero();
        assertThatCode(() -> none.record(new DownloadTracker.Hit("t", "r", ECO, "c", "1.0.0")))
                .as("recording is a no-op that never blocks or fails the read it observes")
                .doesNotThrowAnyException();
    }

    @Test
    void resolve_answers_none_when_no_downloads_module_is_installed() {
        DownloadTrackerProvider.Inventories inventories =
                (tenant, repository) -> new StoreRepositoryInventory(backend);

        DownloadTracker resolved = DownloadTrackerProvider.resolve(inventories, key -> null);

        assertThat(resolved).as("no provider on the module path resolves to the shared NONE singleton")
                .isSameAs(DownloadTracker.NONE);
    }
}
