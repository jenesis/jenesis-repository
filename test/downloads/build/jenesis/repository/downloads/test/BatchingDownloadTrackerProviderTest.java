package build.jenesis.repository.downloads.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.downloads.BatchingDownloadTracker;
import build.jenesis.repository.downloads.BatchingDownloadTrackerProvider;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BatchingDownloadTrackerProvider} as the discovered {@link DownloadTrackerProvider}: {@link ServiceLoader}
 * finds it on the module path, it answers to the {@code batching} name, and {@link DownloadTrackerProvider#resolve
 * resolve} - the seam the composition names no implementation through - yields a {@link BatchingDownloadTracker}
 * whose enablement follows the {@code track-downloads} dial. With this module installed {@code resolve} never falls
 * back to {@link DownloadTracker#NONE}; the graceful-absence path is covered where the module is <em>absent</em>.
 */
class BatchingDownloadTrackerProviderTest {

    @TempDir
    Path root;

    private DownloadTrackerProvider.Inventories inventories;

    @BeforeEach
    void setUp() {
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        inventories = (tenant, repository) -> new StoreRepositoryInventory(backend);
    }

    @Test
    void service_loader_discovers_the_batching_provider() {
        List<DownloadTrackerProvider> providers = ServiceLoader.load(DownloadTrackerProvider.class)
                .stream().map(ServiceLoader.Provider::get).toList();

        assertThat(providers)
                .as("the downloads module provides a DownloadTrackerProvider discovered via ServiceLoader")
                .hasAtLeastOneElementOfType(BatchingDownloadTrackerProvider.class);
        assertThat(providers).anyMatch(provider -> provider.name().equals("batching"));
    }

    @Test
    void the_provider_answers_to_the_batching_name() {
        assertThat(new BatchingDownloadTrackerProvider().name()).isEqualTo("batching");
    }

    @Test
    void resolve_yields_a_batching_tracker_enabled_when_the_dial_is_on() {
        DownloadTracker resolved = DownloadTrackerProvider.resolve(inventories,
                key -> "track-downloads".equals(key) ? "true" : null);

        assertThat(resolved).as("the installed provider resolves to its tracker, not the NONE fallback")
                .isInstanceOf(BatchingDownloadTracker.class)
                .isNotSameAs(DownloadTracker.NONE);
        assertThat(resolved.enabled()).as("track-downloads=true enables the tracker").isTrue();
    }

    @Test
    void resolve_yields_a_disabled_tracker_when_the_dial_is_off() {
        DownloadTracker resolved = DownloadTrackerProvider.resolve(
                inventories, key -> "track-downloads".equals(key) ? "false" : null);

        assertThat(resolved).as("the tracker still stands when off, so a health surface reads 'installed but off'")
                .isInstanceOf(BatchingDownloadTracker.class);
        assertThat(resolved.enabled()).as("the dial still switches the tracker off").isFalse();
        assertThat(resolved.alive()).as("a disabled tracker's worker is not started").isFalse();
    }

    @Test
    void create_reflects_the_track_downloads_dial() {
        BatchingDownloadTrackerProvider provider = new BatchingDownloadTrackerProvider();

        Optional<DownloadTracker> on = provider.create(inventories,
                key -> "track-downloads".equals(key) ? "true" : null);
        assertThat(on).as("create yields a tracker when the module is installed").isPresent();
        assertThat(on.get().enabled()).isTrue();
        assertThat(((BatchingDownloadTracker) on.get()).flushInterval())
                .as("an unset flush interval is the shipped default")
                .contains(BatchingDownloadTracker.DEFAULT_FLUSH_INTERVAL);

        Optional<DownloadTracker> hourly = provider.create(inventories,
                key -> "download-flush-interval".equals(key) ? "PT1H" : "true");
        assertThat(((BatchingDownloadTracker) hourly.get()).flushInterval()).contains(Duration.ofHours(1));
        Optional<DownloadTracker> eager = provider.create(inventories,
                key -> "download-flush-interval".equals(key) ? "off" : "true");
        assertThat(((BatchingDownloadTracker) eager.get()).flushInterval())
                .as("off is a flush on every drain").isEmpty();

        Optional<DownloadTracker> off = provider.create(inventories,
                key -> "track-downloads".equals(key) ? "false" : null);
        assertThat(off).isPresent();
        assertThat(off.get().enabled()).as("the dial still switches the tracker off").isFalse();
    }
}
