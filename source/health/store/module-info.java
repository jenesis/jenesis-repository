/**
 * The store-backed maintainer-health ledger: a {@link build.jenesis.repository.health.HealthLedgerProvider} keeping
 * each coordinate's health as the {@code health} section of its per-coordinate metadata document (health is a property
 * of the project), upserted under compare-and-set so concurrent writers converge on the freshest score. It also
 * provides the scheduled {@link build.jenesis.repository.health.store.HealthScanTask} that fills the ledger, the
 * rank-index pass, and the {@link build.jenesis.repository.maintenance.StorageNamespace} for what lives outside the
 * document. Reclamation rides eviction of a coordinate's last version. Without this module the health SPI resolves to
 * nothing and every surface falls back to the live probe.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.health.store {
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.bounds;
    requires build.jenesis.repository.walk;
    requires tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.health.store to
            build.jenesis.repository.health.test, build.jenesis.repository.compliance.test,
            build.jenesis.repository.reclamation.test,
            build.jenesis.repository.server.kernel.test;
    provides build.jenesis.repository.health.HealthLedgerProvider
            with build.jenesis.repository.health.store.StoreHealthLedgerProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.health.store.HealthStorageNamespace;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.health.store.HealthScanTaskProvider,
                    build.jenesis.repository.health.store.HealthRankIndexTaskProvider;
}
