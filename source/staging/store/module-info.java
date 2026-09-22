/**
 * The artifact-store-backed staging lifecycle as a plugin module: it provides
 * {@link build.jenesis.repository.staging.StagingProvider} answering to {@code store}, so the repository discovers
 * staging through {@code ServiceLoader} and a deployment without this module simply runs without staging. A deploy
 * is held under a staging view that does not resolve; promotion re-publishes every held artifact through its own
 * format's layout (discovered like the router's), records it in the inventory, and never copies bytes. The
 * {@link build.jenesis.repository.staging.store.StagingReapTask} (riding the scheduled cleanup pass's enablement)
 * drops abandoned-OPEN stagings and sealed markers past the {@code staging-ttl}, so neither key space grows forever.
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
