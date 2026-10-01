package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the published-index pass: enabled by {@code index}, its cadence from {@code index-interval} (a day by
 * default), its chunk size from {@code index-max-chunk} (8 MiB) and its rebase cadence from
 * {@code index-rebase-interval} (a week, at most a month). Off unless enabled. Both cadences are
 * {@link IntervalSetting} constants that {@link IndexSettingsContributor} renders, so the catalogue cannot drift from
 * the code.
 */
public final class PublishedIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the published index is appended to. */
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

    /** The configured chunk rotation size, or its default; the walk's rebase sizes chunks by it too. */
    static long maxChunk(UnaryOperator<String> config) {
        return bytes(config.apply("index-max-chunk"), DEFAULT_MAX_CHUNK);
    }

    /** A byte size, or {@code fallback} when blank, malformed or not positive. */
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
