/**
 * The full-text search index, on Apache Lucene, for the repositories that ask for one
 * ({@link build.jenesis.repository.search.SearchMode#FULL_TEXT}; off by default). A
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code search-index} runs the
 * lease-guarded single-writer pass that bootstraps an index from the version documents and applies what the publish
 * observer marks, storing content-addressed segments under {@code index/search/segments} and cutting the
 * {@code index/search/current} manifest over by compare-and-set. Beside it: the observer, the walk consumer that
 * rebuilds from truth (and removes a switched-off index), the settings, and a
 * {@link build.jenesis.repository.search.SearchQueryProvider}. The index is derived data in the repository's own store:
 * another format version is rebuilt, never migrated.
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
    // How a version's declared licences resolve, for the licence filter terms each document carries.
    requires build.jenesis.repository.compliance.inventory;
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
