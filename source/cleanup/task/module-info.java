/**
 * The retention engine and the scheduled cleanup pass: a {@link build.jenesis.repository.cleanup.RetentionProvider}, a
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code cleanup}, the retention
 * settings and the walk consumers for retention and roll-up - so a deployment without this module runs without
 * retention.
 *
 * <p>The scheduled pass reaps finished import and export jobs and reconciles a quota'd tenant's usage. Retention,
 * garbage collection and the subtree-size roll-up are walk consumers, carried daily by default by the walks setting's
 * {@code retention} entry; the collector is the discovered
 * {@code build.jenesis.repository.gc.GarbageCollectorProvider}, and with none resolved retention evicts but nothing is
 * reclaimed. Job records are small JSON read with Jackson.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cleanup.task {
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.walk;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    provides build.jenesis.repository.gc.walk.GcRoots
            with build.jenesis.repository.cleanup.task.InventoryGcRoots;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.cleanup.task.RetentionConsumer,
                    build.jenesis.repository.cleanup.task.RollUpConsumer;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.cleanup.task;
    provides build.jenesis.repository.cleanup.RetentionProvider
            with build.jenesis.repository.cleanup.task.RepositoryCleanerProvider;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.cleanup.task.CleanupTaskProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.cleanup.task.RetentionSettingsContributor;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.cleanup.task.ReclamationCapabilityContributor;
}
