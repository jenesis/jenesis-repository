package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the search-index pass, its cadence from {@code search-index-interval} (default ten minutes). The pass is
 * always installed and decides per repository: a repository whose {@code full-text-search} setting is off - the
 * default - costs it nothing, not a read, and one that has it on is bootstrapped and kept current. With the shared
 * artifact walk installed a bootstrap enumerates over its own resumable {@code walks/search} pass (the snapshot
 * itself stays a single-writer restart-on-crash rebuild - the task keeps its lease); without one it keeps the
 * complete-per-call streaming enumeration.
 *
 * <p>The cadence is held as an {@link IntervalSetting} constant and rendered into {@link SearchSettingsContributor}
 * from it, so the catalogue default cannot drift from the code.
 */
public final class SearchIndexTaskProvider implements MaintenanceTaskProvider {

    /** How often the search index applies what changed; ten minutes by default. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("search-index-interval", "PT10M");

    /** How long an uncommitted rebuild's claim on a generation is honoured before the next rebuild takes it over as a
     *  dead node's: the takeover a paused or crashed node's rebuild costs the fleet, a ten-minute default. */
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
