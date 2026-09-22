package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the published-index pass: enabled by the {@code index} setting, its cadence from {@code index-interval}
 * (default a day), its chunk rotation size from {@code index-max-chunk} (default 8 MiB) and its full-snapshot rebase
 * cadence from {@code index-rebase-interval} (default a week, at most a month). Off unless enabled, so a deployment
 * publishes no index until it opts in - and a deployment without this module has no published index at all.
 *
 * <p>Both cadences are held as {@link IntervalSetting} constants and rendered into {@link IndexSettingsContributor}
 * from these constants, so neither a catalogue default nor the rebase maximum can drift from the code.
 */
public final class PublishedIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the published index is appended to; daily by default. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("index-interval", "P1D");


    private static final long DEFAULT_MAX_CHUNK = 8L * 1024 * 1024;

    @Override
    public String name() {
        return "index";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Boolean.parseBoolean(config.apply("index"))) {
            return Optional.empty();
        }
        return Optional.of(new PublishedIndexTask(INTERVAL.resolve(config), maxChunk(config)));
    }

    /** The chunk rotation size the deployment configured, or its default - what the walk's rebase sizes chunks by too. */
    static long maxChunk(UnaryOperator<String> config) {
        return bytes(config.apply("index-max-chunk"), DEFAULT_MAX_CHUNK);
    }

    /** The chunk rotation size, or its default - a malformed or non-positive value falls back for the same reason. */
    private static long bytes(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
