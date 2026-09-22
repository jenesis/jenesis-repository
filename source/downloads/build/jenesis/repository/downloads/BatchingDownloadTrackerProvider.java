package build.jenesis.repository.downloads;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;

/**
 * Discovers the batching download tracker: recording is off unless {@code track-downloads} switches it on, and a
 * disabled tracker still stands (its worker reports as off) so a health surface distinguishes "installed but off"
 * from a dead worker.
 */
public final class BatchingDownloadTrackerProvider implements DownloadTrackerProvider {

    @Override
    public String name() {
        return "batching";
    }

    @Override
    public Optional<DownloadTracker> create(Inventories inventories, UnaryOperator<String> config) {
        return Optional.of(new BatchingDownloadTracker(inventories,
                Features.enabled(config, "track-downloads"),
                flushInterval(config.apply("download-flush-interval"))));
    }

    /** The dial's value: absent is the shipped default, {@code 0} or {@code off} is a flush on every drain. */
    static Duration flushInterval(String value) {
        if (value == null || value.isBlank()) {
            return BatchingDownloadTracker.DEFAULT_FLUSH_INTERVAL;
        }
        String trimmed = value.trim();
        if (trimmed.equals("0") || trimmed.equalsIgnoreCase("off")) {
            return null;
        }
        return Durations.parse(trimmed);
    }
}
