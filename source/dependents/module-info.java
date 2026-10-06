/**
 * The reverse-dependency index and its scheduled sweep: a
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code dependents} inverts the
 * CycloneDX dependency graph read out of every stored artifact into a sharded "who depends on X" index, and a
 * deployment without this module has none. The read model,
 * {@link build.jenesis.repository.dependents.DependentsQueryReader}, is published as the discovered
 * {@link build.jenesis.repository.dependents.spi.DependentsQueryProvider}, so the API, console and CLI read dependents
 * without depending on this module and without the build machinery. Both paths share one layout and codec
 * ({@code DependentsStore}). The index is a bounded set of shard objects committed by compare-and-set, and the sweep
 * never buffers an artifact - only its embedded BOM. With the shared walk installed the sweep is a resumable,
 * segmented, multi-node {@code walks/dependents} pass; without it, an exclusive complete recompute.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.dependents {
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.dependency;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires org.slf4j;
    exports build.jenesis.repository.dependents;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.dependents.DependentsPublicationObserver;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.dependents.DependentsIndexTaskProvider;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.dependents.DependentsRebuildConsumer;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.dependents.DependentsStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.dependents.DependentsSettingsContributor;
    provides build.jenesis.repository.dependents.spi.DependentsQueryProvider
            with build.jenesis.repository.dependents.DependentsIndexProvider;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.dependents.DependentsCapabilityContributor;
}
