/**
 * The store-backed maintainer-health ledger as a plugin module: it provides
 * {@link build.jenesis.repository.health.HealthLedgerProvider}, keeping each coordinate's health as one small JSON
 * document at the {@code health/<ecosystem>/<coordinate>} key the SPI contract fixes (version-independent - health is a
 * property of the project), upserted last-writer-wins under the store's compare-and-set so concurrent writers (the
 * health sweep, the publish-time persistence, an on-demand rescan) converge on the freshest answer. It also provides the
 * scheduled {@link build.jenesis.repository.health.store.HealthScanTask} that populates the ledger from the live health
 * source and stamps its freshness, and the {@link build.jenesis.repository.maintenance.StorageNamespace} declaring the
 * key-space in the storage manifest; its reclamation rides the artifact's own lifecycle - the inventory's
 * {@code evict} deletes a coordinate's record when its last published version goes. With this module absent the health
 * SPI resolves to nothing and every writer and surface degrades to the live-probe behaviour.
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
