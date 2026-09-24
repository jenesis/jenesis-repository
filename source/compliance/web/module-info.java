/**
 * The compliance-review HTTP surface as a removable server feature module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports its configuration
 * through {@code ServiceLoader} discovery and names no quarantine, vulnerability or provenance endpoint. A thin Spring
 * {@code web} adapter over the framework-free gate and advisory SPIs (resolved per tenant-and-repository through
 * {@code Repositories}): the {@code QuarantineController} reviews and releases what the discovered compliance
 * screen held back, the {@code VulnerabilityController} re-scans stored inventory against the discovered advisory feed,
 * and the {@code ProvenanceController} signs and serves the attestation of a stored artifact's identity over the
 * discovered {@code ProvenanceSigner} (never re-read or re-hashed off the store; {@code 404} when no signer is
 * configured). With this module absent the server carries none of the surface and the console hides the panels. Open so
 * Spring can reflect over the controllers and their configuration.
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
    // The console seam this module contributes its screens through, and the read services they render. The
    // console never learns the screening vocabulary: a deployment without this module simply has no such screen.
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
