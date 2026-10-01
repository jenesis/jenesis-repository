/**
 * The scheduled vulnerability passes as a plugin module, each a
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider}: {@code scan} counts each repository against the
 * advisory and known-exploited feeds and publishes the counts as gauges; {@code kev-enforce} holds an already-published
 * artifact whose CVE a known-exploited catalogue lists, writing the gate's {@code /quarantine} hold and
 * {@link build.jenesis.repository.gate.QuarantineLog} row; {@code reanalyze} releases such a hold once its intel clears
 * ({@link build.jenesis.repository.gate.KevHold#cleared}); {@code vulnerability-rank-index} keeps the worst-first rank
 * index current; and {@code signal-refresh} draws a mirroring signal source's snapshot so its query path renders rather
 * than fetches. Without this module there are no scan gauges and no retroactive holds.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.scan {
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.bounds;
    requires build.jenesis.repository.gate.spi;
    requires tools.jackson.databind;
    requires org.slf4j;
    // The console's template engine reads the report's records reflectively; only this package is opened.
    opens build.jenesis.repository.compliance.scan;

    exports build.jenesis.repository.compliance.scan to build.jenesis.repository.compliance.web,
            build.jenesis.repository.ui.store,
            build.jenesis.repository.ui.admin.installed.test,
            build.jenesis.repository.server.kernel.test, build.jenesis.repository.recovery.test,
            build.jenesis.repository.maintenance.contract.test;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.compliance.scan.VulnerabilityScanTaskProvider,
                 build.jenesis.repository.compliance.scan.KevEnforceTaskProvider,
                 build.jenesis.repository.compliance.scan.ReanalysisTaskProvider,
                 build.jenesis.repository.compliance.scan.VulnerabilityRankIndexTaskProvider,
                 build.jenesis.repository.compliance.scan.SignalRefreshTaskProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.compliance.scan.VulnerabilityRankStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.scan.ScanSettingsContributor;
    provides build.jenesis.repository.server.spi.CapabilityContributor
            with build.jenesis.repository.compliance.scan.ScanCapabilityContributor;
}
