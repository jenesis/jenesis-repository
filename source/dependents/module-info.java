/**
 * The declared-dependencies index and its scheduled pass: a
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code dependents} records, for each
 * package, the published versions whose manifest declares a dependency on it and the requirement each states, and a
 * deployment without this module has none. The read model, {@link build.jenesis.repository.dependents.DependentsQueryReader},
 * is published as the discovered {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider}, so the API,
 * console and CLI read declarations without depending on this module and without the pass. Both paths share one layout
 * and codec ({@code DependentsStore}); every shard is committed by compare-and-set.
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.dependents {
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.dependents;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.dependents.DependentsIndexTaskProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.dependents.DependentsStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.dependents.DependentsSettingsContributor;
    provides build.jenesis.repository.dependents.spi.DependentsQueryProvider
            with build.jenesis.repository.dependents.DependentsIndexProvider;
}
