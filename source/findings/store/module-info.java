/**
 * The store-backed findings ledger as a plugin module: it provides
 * {@link build.jenesis.repository.findings.FindingsProvider}, keeping each coordinate version's findings as one
 * small JSON document at the {@code findings/<ecosystem>/<coordinate>/<version>} key the SPI contract fixes,
 * merged under the store's compare-and-set so concurrent writers (the scan sweep, the gate, an on-demand report)
 * converge instead of losing rows. The key-space is declared in the storage manifest; its reclamation rides
 * the artifact's own lifecycle - the inventory's {@code evict} deletes the version's document with the other
 * derived sidecars, and a discarded quarantine hold takes its gate findings through the
 * {@link build.jenesis.repository.gate.HoldReleaseObserver} this module provides. With this module absent the
 * findings SPI resolves to nothing and every writer and surface degrades to the live-feed behaviour.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.findings.store {
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.bounds;
    requires tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.findings.store to
            build.jenesis.repository.findings.test, build.jenesis.repository.reclamation.test,
            build.jenesis.repository.server.kernel.test, build.jenesis.repository.ui.admin.installed.test;
    provides build.jenesis.repository.findings.FindingsProvider
            with build.jenesis.repository.findings.store.StoreFindingsProvider;
    provides build.jenesis.repository.gate.HoldReleaseObserver
            with build.jenesis.repository.findings.store.DiscardedHoldFindingsObserver;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.findings.store.FindingsStorageNamespace;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.findings.store.FindingsFilterIndexTaskProvider;
}
