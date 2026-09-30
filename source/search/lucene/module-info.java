/**
 * The full-text search index, Apache Lucene as a library rather than a new stack, for the repositories that ask for
 * one ({@link build.jenesis.repository.search.SearchMode#FULL_TEXT}; off by default, when a repository answers by name
 * and none of this runs). It provides a {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering
 * to {@code search-index}: the {@code Lease}-guarded, single-writer background pass that bootstraps a repository's
 * index from its published versions - the version documents only, never an artifact blob - and then applies what the
 * publish observer marks, storing the index as content-addressed segment files under {@code index/search/segments}
 * and cutting a small {@code index/search/current} manifest over by compare-and-set. Beside it: the observer that
 * marks a publish or a removal, the walk consumer that rebuilds from truth when a walk carrying it completes (and
 * removes the index of a repository that has switched it off), its settings, and a
 * {@link build.jenesis.repository.search.SearchQueryProvider} whose per-repository searcher swaps a new generation in
 * behind a short refresh window. The index is derived data: a manifest of another format version is discarded and
 * rebuilt by the next pass, never migrated, and it lives in the same scoped store the repository already writes to -
 * no database, no second service.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.search.lucene {
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    requires org.slf4j;
    requires org.apache.lucene.core;
    requires org.apache.lucene.analysis.common;
    exports build.jenesis.repository.search.lucene;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.search.lucene.SearchPublicationObserver;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.search.lucene.SearchRebuildConsumer;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.search.lucene.SearchRebuildConsumer.Observability;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.search.lucene.SearchIndexTaskProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.search.lucene.SearchStorageNamespace;
    provides build.jenesis.repository.search.SearchQueryProvider
            with build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.search.lucene.SearchSettingsContributor;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.search.lucene.SearchCapabilityContributor;
}
