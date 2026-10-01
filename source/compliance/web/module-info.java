/**
 * The compliance-review HTTP surface and console screens as a removable module: a
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider} and a console module over the gate, advisory and
 * findings SPIs - quarantine review, vulnerabilities, findings, health, provenance, signatures. Without it the server
 * carries none of the surface. Open for Spring's reflection.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.web {
    exports build.jenesis.repository.compliance.web to build.jenesis.repository.server.kernel.test,
            build.jenesis.repository.ui.admin.installed.test, build.jenesis.repository.recovery.test;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.compliance.scan;
    requires build.jenesis.repository.compliance.signatures;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.gateway;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    // The console seam this module's screens are contributed through.
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.store;
    requires thymeleaf.spring6;
    requires jakarta.servlet;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires tools.jackson.databind;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.compliance.web.ComplianceConsoleModule;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.compliance.web.ComplianceWebModule;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.web.ProvenanceSweepSettingsContributor;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.compliance.web.ProvenanceAttestationStorageNamespace;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.compliance.web.ProvenanceAttestationReaper;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.compliance.web.ProvenanceAttestationSweepProvider;
}
