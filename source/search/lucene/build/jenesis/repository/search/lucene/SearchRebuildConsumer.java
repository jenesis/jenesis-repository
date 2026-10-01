package build.jenesis.repository.search.lucene;

import module java.base;

import build.jenesis.repository.compliance.LicenseTable;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;

/**
 * The search index rebuilt from truth at the end of a walk: the full reconcile that heals whatever the change feed
 * missed and compacts the feed - a Lucene rebuild over every release, too costly to run every few hours. The pass
 * keeps its bootstrap (no usable index yet) and its steady state (the dirty feed); this consumer runs the reconcile
 * when a walk carrying it completes, weekly by default on the rebuild entry, and its own accumulation still rides the
 * index's own walk pass, whose completeness rule (one worker, a fresh generation) is bound to that pass.
 *
 * <p>It is also where an index a repository no longer asks for goes: a repository whose {@code full-text-search} is
 * off and still holds an index - it was on, and was switched off - has it removed here, which costs the walk one
 * existence probe per repository with full-text search off. The document
 * count and snapshot size are on the observability report as {@code jenrepo.search.*}. Listens on the pointer stream
 * only to be told which store's pass it is riding.
 */
public final class SearchRebuildConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenrepo.search-rebuild}) and how a walk entry names it. */
    public static final String NAME = "search-rebuild";

    private static final Map<Object, Map<String, Double>> LAST = new ConcurrentHashMap<>();

    private final Set<Object> riding = ConcurrentHashMap.newKeySet();

    /** Each store's repository configuration, handed over before the pass over it starts. */
    private final Map<Object, UnaryOperator<String>> configs = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Rebuilds the full-text index of each repository that has one on from every published release at the end "
                + "of the walk, and compacts its change feed; reads every inventory row over its own pass and writes "
                + "the whole index once. Removes the index of a repository that has switched full-text search off.";
    }

    @Override
    public void onRepository(ArtifactStore store, UnaryOperator<String> config) {
        configs.put(store.identity(), config);
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassStarted(WalkPass pass, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        UnaryOperator<String> config = configs.remove(store.identity());
        if (!riding.remove(store.identity())) {
            return;
        }
        // A driver that names no repository hands no configuration, and the deployment's answers for it.
        UnaryOperator<String> effective = config == null ? Features.settings() : config;
        try {
            if (SearchMode.of(effective) != SearchMode.FULL_TEXT) {
                SearchIndex index = new SearchIndex(store);
                if (index.exists()) {
                    index.remove();
                }
                LAST.remove(store.identity());
                return;
            }
            Map<String, Double> gauges = new ConcurrentHashMap<>();
            new SearchIndexTask(Duration.ZERO, WalkProvider.resolve(Features.settings()).orElse(null))
                    .rebuild(store, SearchIndexTaskProvider.CLAIM.resolve(effective), LicenseTable.of(effective),
                            (name, description, value) -> gauges.put(name, value));
            LAST.put(store.identity(), gauges);
        } catch (IOException unrebuilt) {
            throw new UncheckedIOException(unrebuilt);
        }
    }

    /** The last rebuild's gauges, summed over the repositories this node rebuilt, on the observability report. */
    public static final class Observability implements ObservabilitySource {

        public Observability() {
        }

        @Override
        public List<Metric> metrics() {
            double documents = 0;
            double bytes = 0;
            for (Map<String, Double> gauges : LAST.values()) {
                documents += gauges.getOrDefault("jenrepo.search.documents", 0.0);
                bytes += gauges.getOrDefault("jenrepo.search.bytes", 0.0);
            }
            return List.of(
                    Metric.gauge("jenrepo.search.documents", "Documents in the search index, summed over the last "
                            + "walk-driven rebuild of each repository.", documents, "documents"),
                    Metric.gauge("jenrepo.search.bytes", "Compressed size of the search index snapshots, summed over "
                            + "the last walk-driven rebuild of each repository.", bytes, "bytes"));
        }
    }
}
