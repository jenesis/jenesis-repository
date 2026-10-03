package build.jenesis.repository.downloads;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.DownloadTrackerProvider;

/**
 * The batching download tracker: recording is on unless {@code track-downloads} switches it off, and a disabled
 * tracker still stands, its worker reporting as off, so a health surface tells "installed but off" from a dead worker.
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
        Duration interval = Durations.dial(value, Features.key("download-flush-interval"),
                BatchingDownloadTracker.DEFAULT_FLUSH_INTERVAL);
        return interval.isZero() ? null : interval;
    }
}
