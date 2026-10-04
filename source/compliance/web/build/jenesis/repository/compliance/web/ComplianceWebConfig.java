package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.ComplianceSources;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the compliance-review API into the repository server - quarantine, vulnerabilities, findings, health,
 * provenance, signatures, signers, hardening, licence retro - over {@link Repositories}, {@link AuditTrail}, the
 * {@link ComplianceSources} the live settings switch on. Imported
 * through {@link ComplianceWebModule}; every controller is registered explicitly. The VEX API is the VEX store's own
 * web module.
 */
@Configuration(proxyBeanMethods = false)
public class ComplianceWebConfig {

    @Bean
    public QuarantineController quarantineController(Repositories repositories, RepositoryRouting routing,
                                                     AuditTrail audit) {
        return new QuarantineController(repositories, routing, audit);
    }

    @Bean
    public SignatureController signatureController(Repositories repositories, RepositoryRouting routing) {
        return new SignatureController(repositories, routing);
    }

    @Bean
    public SignersController signersController(Repositories repositories, RepositoryRouting routing) {
        return new SignersController(repositories, routing);
    }

    @Bean
    public HardeningVerdictController hardeningVerdictController(Repositories repositories,
                                                                 RepositoryRouting routing) {
        return new HardeningVerdictController(repositories, routing);
    }

    @Bean
    public VulnerabilityController vulnerabilityController(Repositories repositories, RepositoryRouting routing,
                                                           ComplianceSources sources) {
        // The feeds merged for a report and by name for the ledger, so it records which feed reported an advisory -
        // each asked at use, so a feed switched on is the one a report reads.
        return new VulnerabilityController(repositories, routing, sources::advisories, sources::advisoryFeeds,
                sources::advisorySignals);
    }

    @Bean
    public FindingsController findingsController(Repositories repositories, RepositoryRouting routing, AuditTrail audit,
                                                 LiveConfig liveConfig) {
        // The gate a publish into the tenant meets, read live.
        return new FindingsController(repositories, routing, audit, liveConfig::publishGate);
    }

    @Bean
    public HealthController healthController(Repositories repositories, RepositoryRouting routing,
                                             ComplianceSources sources,
                                             ObjectProvider<MaintenanceScheduler> maintenance) {
        // The scheduler is optional (absent on a read-only node) and supplied lazily, so its workers do not start
        // early.
        return new HealthController(repositories, routing, sources::health, maintenance::getIfAvailable);
    }

    @Bean
    public ProvenanceController provenanceController(Repositories repositories, RepositoryRouting routing,
                                                     ComplianceSources sources, AuditTrail audit) {
        return new ProvenanceController(repositories, routing, sources::provenanceSigner, audit);
    }

    @Bean
    public LicenseRetroController licenseRetroController(Repositories repositories, RepositoryRouting routing,
                                                        Settings settings, Environment environment,
                                                        PinnedSettings pins) {
        return new LicenseRetroController(repositories, routing, settings, environment, pins);
    }
}
