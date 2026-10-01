/**
 * The store-backed staging lifecycle: a {@link build.jenesis.repository.staging.StagingProvider} answering to
 * {@code store}; without this module a deployment runs without staging. A deploy is held under a staging view that does
 * not resolve; promotion re-publishes each held artifact through its format's layout and records it in the inventory,
 * copying no bytes. {@link build.jenesis.repository.staging.store.StagingReapTask} drops abandoned stagings and sealed
 * markers past {@code staging-ttl}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.staging.store {
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.staging.store to
            build.jenesis.repository.staging.store.test, build.jenesis.repository.reclamation.test;
    provides build.jenesis.repository.staging.StagingProvider
            with build.jenesis.repository.staging.store.StoreStagingProvider;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.staging.store.StagingReapTaskProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.staging.store.StagingStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.staging.store.StagingSettingsContributor;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.staging.store.StagingWithholdInterceptor;
}
