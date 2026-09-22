/**
 * The retention engine and its scheduled pass as a plugin module: it provides
 * {@link build.jenesis.repository.cleanup.RetentionProvider} (the on-demand plan/sweep engine) and
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code cleanup}, plus the
 * retention settings - so a deployment without this module runs without retention, and installing it adds the
 * endpoints' engine, the background pass and the settings in one step. The import-job status object is small
 * machine-written JSON, parsed with the Jackson databind already on the server path (a library, not a hand-rolled
 * reader).
 *
 * <p>What the scheduled pass does is narrower than it was: the reaps that are neither a walk nor a repair, which
 * are the import-job auto-dismiss and a quota'd tenant's usage reconciliation. Retention, garbage collection and
 * the browse's subtree-size roll-up are walk consumers instead - {@code RetentionConsumer} judges inventory rows as
 * the walk streams them, {@code GcConsumer} and {@code RollUpConsumer} run at the end of a pass that carries them,
 * and the {@code retention} entry of the walks setting carries all three daily by default. The collector itself is
 * still the discovered {@code build.jenesis.repository.gc.GarbageCollectorProvider} capability, and with none
 * resolved the walk evicts but reclaims nothing.
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
    exports build.jenesis.repository.cleanup.task to
            build.jenesis.repository.cleanup.test, build.jenesis.repository.gateway.test,
            build.jenesis.repository.server.kernel.test, build.jenesis.repository.reclamation.test,
            build.jenesis.repository.gateway.contract.test;
    provides build.jenesis.repository.cleanup.RetentionProvider
            with build.jenesis.repository.cleanup.task.RepositoryCleanerProvider;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.cleanup.task.CleanupTaskProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.cleanup.task.RetentionSettingsContributor;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.cleanup.task.ReclamationCapabilityContributor;
}
