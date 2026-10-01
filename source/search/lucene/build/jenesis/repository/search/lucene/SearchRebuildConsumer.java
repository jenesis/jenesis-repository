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
 * The search index rebuilt from truth when a walk carrying this consumer completes: the reconcile that heals what the
 * change feed missed and compacts it, weekly by default. The pass keeps its bootstrap and its steady state, and the
 * accumulation rides the index's own walk pass, whose completeness rule is bound to it.
 *
 * <p>It also removes the index of a repository whose {@code full-text-search} was switched off, one existence probe per
 * such repository. The document count and snapshot size are reported as {@code jenrepo.search.*}. It listens on the
 * pointer stream only to learn which store's pass it rides.
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
