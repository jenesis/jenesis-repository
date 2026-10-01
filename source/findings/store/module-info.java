/**
 * The store-backed findings ledger: a {@link build.jenesis.repository.findings.FindingsProvider} keeping each
 * coordinate version's findings as the {@code findings} section of its metadata document, merged by compare-and-set so
 * concurrent writers (the scan sweep, the gate, an on-demand report) converge. Reclamation rides the artifact's
 * lifecycle: eviction deletes the document, and a discarded quarantine hold drops its findings through the
 * {@link build.jenesis.repository.gate.HoldReleaseObserver} this module provides. Without this module the findings SPI
 * resolves to nothing and every writer and surface falls back to the live feeds.
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
    exports build.jenesis.repository.findings.store;
    provides build.jenesis.repository.findings.FindingsProvider
            with build.jenesis.repository.findings.store.StoreFindingsProvider;
    provides build.jenesis.repository.gate.HoldReleaseObserver
            with build.jenesis.repository.findings.store.DiscardedHoldFindingsObserver;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.findings.store.FindingsStorageNamespace;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.findings.store.FindingsFilterIndexTaskProvider;
}
