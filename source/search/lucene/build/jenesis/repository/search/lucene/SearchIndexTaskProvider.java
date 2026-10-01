package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the search-index pass, paced by {@code search-index-interval} (ten minutes by default). Always installed; a
 * repository with {@code full-text-search} off, the default, costs it nothing. With the shared walk installed a
 * bootstrap enumerates over its resumable {@code walks/search} pass; without it, by a streaming walk. The cadence is an
 * {@link IntervalSetting} rendered into {@link SearchSettingsContributor}.
 */
public final class SearchIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the search index applies what changed; ten minutes by default. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("search-index-interval", "PT10M");

    /** How long an uncommitted rebuild's claim on a generation is honoured before the next rebuild takes it over. */
    static final IntervalSetting CLAIM = IntervalSetting.of("search-index-claim", SearchIndex.STALE_CLAIM_TEXT);

    @Override
    public String name() {
        return "search-index";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        return Optional.of(new SearchIndexTask(INTERVAL.resolve(config), WalkProvider.resolve(config).orElse(null)));
    }
}
