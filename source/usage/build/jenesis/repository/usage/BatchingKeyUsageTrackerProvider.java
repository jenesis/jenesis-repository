package build.jenesis.repository.usage;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.KeyUsageTrackerProvider;

/**
 * The batching usage tracker: recording is on unless {@code track-key-usage} switches it off, and a disabled tracker
 * still stands, its worker reporting as off, so a health surface tells "installed but off" from a dead worker.
 */
public final class BatchingKeyUsageTrackerProvider implements KeyUsageTrackerProvider {

    @Override
    public String name() {
        return "batching";
    }

    @Override
    public Optional<KeyUsageTracker> create(Authorization authorization, UnaryOperator<String> config) {
        return Optional.of(new BatchingKeyUsageTracker(authorization, Features.enabled(config, "track-key-usage")));
    }
}
