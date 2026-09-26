/**
 * The scheduled vulnerability re-scan as a plugin module: it provides
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code scan} (the non-exclusive
 * gauge pass), to {@code kev-enforce} (the exclusive retroactive known-exploited hold), to {@code reanalyze} (the
 * exclusive continuous re-analysis / auto-release pass), to {@code vulnerability-rank-index} (the exclusive pass
 * keeping the durable worst-first rank index current) and to {@code signal-refresh} (the exclusive write-role draw
 * that keeps a mirroring signal source's snapshot present, so its query path may render rather than fetch on the
 * publish thread), so the neutral maintenance scheduler discovers all of them and a
 * deployment without this module simply publishes no scan gauges and neither enforces nor walks back a late-arriving
 * known-exploited finding. The gauge pass counts each repository's inventory against the discovered advisory feeds and
 * known-exploited catalogues and reports the vulnerable and known-exploited coordinate counts per repository; the
 * enforcement pass quarantines an already-published artifact whose CVE is on a known-exploited catalogue, writing the
 * same {@code /quarantine} hold and {@link build.jenesis.repository.gate.QuarantineLog} row the publish-time gate writes
 * (through {@link build.jenesis.repository.store.Publication}), so it lands in the normal review queue and the existing
 * release/discard flow applies unchanged; the re-analysis pass is its self-healing mirror, auto-releasing a retroactive
 * hold ({@link build.jenesis.repository.gate.KevHold#cleared}, no override) the moment its known-exploited intel clears
 * and keeping the findings substrate current for served coordinates over the same walk.
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
    // The report's records are rendered by the console's template engine, which reaches them reflectively -
    // the same reason build.jenesis.repository.ui.store is an open module. Opened rather than the whole
    // module, so only the package a template reads is reachable that way.
    opens build.jenesis.repository.compliance.scan;

    exports build.jenesis.repository.compliance.scan to build.jenesis.repository.compliance.web,
            build.jenesis.repository.ui.store,
            // the admin console's own suite, which names the report's types now that the console assembles
            // through the same service the API does rather than building a second report of its own
            build.jenesis.repository.ui.admin.installed.test,
            build.jenesis.repository.server.kernel.test, build.jenesis.repository.recovery.test,
            // The maintenance-task contract kit reads the rank index back through the very Page record the
            // /api/vulnerabilities surface renders, rather than restating the generation layout inside a fixture.
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
