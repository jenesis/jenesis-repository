package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.downloads.BatchingDownloadTracker;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The download-tracking worker, driven synchronously (no thread): a drain writes a coordinate's last-download
 * marker, and a second drain the same day is skipped, refreshing only when the day rolls over.
 */
class DownloadTrackerTest {

    @TempDir
    Path root;

    private Repositories repositories;
    private BatchingDownloadTracker downloads;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
        downloads = new BatchingDownloadTracker(
                (tenant, repo) -> new StoreRepositoryInventory(repositories.store(tenant, repo)), true, Duration.ofDays(1));
    }

    @Test
    void drain_writes_the_download_marker_at_most_once_per_day() throws IOException {
        DownloadTracker.Hit hit = new DownloadTracker.Hit("default", "releases", "org.x", "lib", "1.0");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store("default", "releases"));
        Instant morning = Instant.parse("2026-06-27T08:00:00Z");

        downloads.drain(List.of(hit), morning);
        assertThat(inventory.lastDownloaded("org.x", "lib", "1.0")).contains(morning);

        downloads.drain(List.of(hit), morning.plus(Duration.ofHours(6)));
        assertThat(inventory.lastDownloaded("org.x", "lib", "1.0")).as("same day, skipped").contains(morning);

        Instant nextDay = morning.plus(Duration.ofDays(1));
        downloads.drain(List.of(hit), nextDay);
        assertThat(inventory.lastDownloaded("org.x", "lib", "1.0")).as("new day, refreshed").contains(nextDay);
    }

    @Test
    void the_written_day_map_is_bounded_by_a_single_days_artifacts() throws IOException {
        Instant monday = Instant.parse("2026-06-27T08:00:00Z");
        List<DownloadTracker.Hit> firstDay = new ArrayList<>();
        for (int artifact = 0; artifact < 500; artifact++) {
            firstDay.add(new DownloadTracker.Hit("default", "releases", "org.x", "lib" + artifact, "1.0"));
        }
        downloads.drain(firstDay, monday);
        assertThat(downloads.tracked()).as("today's markers are held to skip a same-day rewrite").isEqualTo(500);

        // The next day only one artifact is downloaded: the 500 stale markers are dropped, not accumulated forever.
        Instant tuesday = monday.plus(Duration.ofDays(1));
        downloads.drain(List.of(new DownloadTracker.Hit("default", "releases", "org.x", "lib0", "1.0")), tuesday);
        assertThat(downloads.tracked())
                .as("the map tracks one day's distinct artifacts, not every artifact ever downloaded").isEqualTo(1);
    }

    @Test
    void the_worker_is_not_alive_until_started_and_joins_on_close() {
        assertThat(downloads.alive()).as("not started").isFalse();
        downloads.start();
        assertThat(downloads.alive()).as("running after start").isTrue();
        downloads.close();
        assertThat(downloads.alive()).as("joined on close").isFalse();
    }

    @Test
    void reads_past_the_queue_capacity_are_counted_as_dropped() {
        DownloadTracker.Hit hit = new DownloadTracker.Hit("default", "releases", "org.x", "lib", "1.0");
        for (int index = 0; index < 100_100; index++) {
            downloads.record(hit);
        }
        assertThat(downloads.dropped()).as("offers past the 100k bound are dropped").isGreaterThan(0);
    }
}
