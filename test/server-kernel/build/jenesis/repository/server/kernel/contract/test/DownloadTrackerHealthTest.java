package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.downloads.BatchingDownloadTracker;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batching download tracker's health surface under a persistently failing store. A tracker whose marker writes
 * always throw once reported {@code alive() == true} and {@code dropped() == 0} while writing nothing - so the
 * not-downloaded-for retention silently aged every artifact toward eviction with no signal. Now each failed marker
 * write is counted and folded into {@link BatchingDownloadTracker#dropped()} (and exposed via
 * {@link BatchingDownloadTracker#writeFailures()}), so a store that never persists is visible. Driven through the
 * public {@code drain} so no worker thread or server boot is needed.
 *
 * <p>Lives here because the downloads module exports its package to the test only and has no test module
 * of its own; a dedicated {@code test/downloads} module would be its natural home.
 */
public class DownloadTrackerHealthTest {

    @Test
    public void a_persistent_write_failure_is_counted_on_the_health_surface() {
        DownloadTrackerProvider.Inventories inventories =
                (tenant, repository) -> new StoreRepositoryInventory(new WriteFailingStore());
        BatchingDownloadTracker tracker = new BatchingDownloadTracker(inventories, true, Duration.ofDays(1));

        tracker.drain(List.of(
                        new DownloadTracker.Hit("default", "releases", "maven", "com.example:app", "1.0.0"),
                        new DownloadTracker.Hit("default", "releases", "maven", "com.example:lib", "2.0.0")),
                Instant.now());

        assertThat(tracker.writeFailures())
                .as("every failed marker write is counted, not silently swallowed").isEqualTo(2);
        assertThat(tracker.dropped())
                .as("the failures surface on the health signal a store-write failure otherwise hid").isEqualTo(2);
    }

    /** A store whose every compare-and-set write throws - the persistent write failure the tracker must make visible;
     *  reads are empty and the rest is unused by {@code recordDownload}. */
    private static final class WriteFailingStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public InputStream open(String key) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public long size(String key) {
            return 0L;
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            return Optional.empty();
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            throw new IOException("store write always fails");
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
