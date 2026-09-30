package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the dials that tune a repository's full-text index once it has one: how often the pass applies what
 * changed, how long a stalled rebuild's claim is honoured, the incremental safety valve, how often the pass
 * reconciles from truth by itself, and the reconcile riding the walk. Whether a repository has an index at all is its
 * {@code full-text-search} setting, declared beside the search that reads it.
 *
 * <p>The cadence entries render their keys and defaults straight off {@link SearchIndexTaskProvider}'s
 * {@code IntervalSetting} constants, so the catalogue and the code cannot drift.
 */
public final class SearchSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(SearchIndexTaskProvider.INTERVAL.key(), "Search", "Search index interval",
                        "How often the search-index pass applies what was published or removed since it last ran, "
                                + "for each repository with full-text search on. An idle pass reads two small "
                                + "objects and writes nothing.",
                        Setting.Kind.DURATION, SearchIndexTaskProvider.INTERVAL.fallbackText(), false).advanced(),
                new Setting(SearchIndexTaskProvider.CLAIM.key(), "Search", "Search index claim",
                        "How long an unfinished rebuild's claim on an index generation is honoured before another "
                                + "node's rebuild takes it over as a dead rebuild's - the cost a crashed or stalled "
                                + "node's rebuild puts on the fleet.",
                        Setting.Kind.DURATION, SearchIndexTaskProvider.CLAIM.fallbackText(), false).advanced(),
                new Setting("search-incremental", "Search", "Incremental search index",
                        "Apply only what changed (from the dirty-index feed) each pass instead of a full rebuild - "
                                + "the O(delta) steady state. Turn off to force a full rebuild every pass (the safety "
                                + "valve); a reconcile and the format-version bump full-rebuild either way.",
                        Setting.Kind.BOOLEAN, "true", false).advanced(),
                new Setting(SearchIndexTask.RECONCILE, "Search", "Search reconcile interval",
                        "How long after its last full reconcile the pass rebuilds a repository's index from truth by "
                                + "itself, healing whatever the change feed missed; unset, the default, leaves the "
                                + "reconcile to the walk: the search-rebuild consumer rebuilds from truth and "
                                + "compacts the feed when a walk carrying it runs (jenrepo.walks).",
                        Setting.Kind.DURATION, "", false).advanced(),
                new Setting(SearchRebuildConsumer.NAME, "Search", "Search rebuild on the walk",
                        "Rebuild the search index of each repository with full-text search on from truth, and "
                                + "compact its change feed, at the end of a walk of the store that carries this "
                                + "consumer (jenrepo.walks): the reconcile that heals whatever the feed missed. It "
                                + "also removes the index of a repository that has switched full-text search off. "
                                + "Off leaves the index to the feed alone.",
                        Setting.Kind.BOOLEAN, "true", true).advanced());
    }
}
