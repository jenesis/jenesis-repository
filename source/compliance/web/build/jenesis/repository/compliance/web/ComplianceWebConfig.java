package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the compliance-review web adapter into the repository server: the {@link QuarantineController} over the
 * framework-free {@link Repositories} resolver and {@link AuditTrail}, the {@link VulnerabilityController} over the
 * inventory, the discovered {@link AdvisorySource} feed and its {@link AdvisorySignal} report columns, the
 * {@link ProvenanceController} over the resolver and the configured {@link ProvenanceSigner}. Imported through
 * {@code ServerModuleProvider} discovery (see {@link ComplianceWebModule}), never named by the server - so with
 * this module absent the server carries no quarantine, vulnerability or provenance endpoints. The VEX API is
 * <em>not</em> here: it is the VEX store's own web module ({@code build.jenesis.repository.compliance.vex.web}),
 * discovered the same way under the store's own toggle, so this adapter no longer requires that store. The beans mirror the constructor injection the monolith
 * performed, so the resolved dependencies are the same ones the server already exposes.
 */
@Configuration(proxyBeanMethods = false)
public class ComplianceWebConfig {

    @Bean
    public QuarantineController quarantineController(Repositories repositories, AuditTrail audit) {
        return new QuarantineController(repositories, audit);
    }

    @Bean
    public SignatureController signatureController(Repositories repositories) {
        // What the gate made of a publisher's signature, read back from durable state. Registered here rather than
        // found by a component scan because this module registers every controller explicitly - a scan would make the
        // surface depend on package layout, and a controller nobody names is one nobody notices is missing.
        return new SignatureController(repositories);
    }

    @Bean
    public SignersController signersController(Repositories repositories) {
        // What one signer signed, and who has signed at all - the read side of the continuity the gate learns.
        return new SignersController(repositories);
    }

    @Bean
    public HardeningVerdictController hardeningVerdictController(Repositories repositories) {
        // The read-only hardened-leg surface: the recorded verdict, recent typed refusals and the drift alarm,
        // assembled from durable state only (no re-screen, no fetch). Present exactly when this module is.
        return new HardeningVerdictController(repositories);
    }

    @Bean
    public VulnerabilityController vulnerabilityController(Repositories repositories,
                                                           AdvisorySource advisories,
                                                           List<AdvisorySignal> advisorySignals,
                                                           Environment environment) {
        // The attributed per-feed view of the same feeds the merged AdvisorySource bean carries, resolved from the
        // same configuration lookup, so the findings ledger records which feed reported an advisory.
        return new VulnerabilityController(repositories, advisories,
                AdvisorySource.named(Features.namespaced(environment::getProperty)),
                advisorySignals);
    }

    @Bean
    public FindingsController findingsController(Repositories repositories, AuditTrail audit) {
        return new FindingsController(repositories, audit);
    }

    @Bean
    public HealthController healthController(Repositories repositories, HealthSource healthSource,
                                             ObjectProvider<MaintenanceScheduler> maintenance) {
        // The durable health read renders the ledger the sweep populates; the live source is consulted only on an
        // explicit refresh=true. The same shared source instance the publish screen and sweep probe, injected by type.
        //
        // The scheduler rides in so refresh=true can build the ranking under the health-rank-index lease rather than
        // leaving a deployment with scheduled-scan off reading "not yet ranked" forever (D-138). ObjectProvider, not a
        // hard dependency: a read-only or embedding deployment runs no scheduler, and the refresh must still persist
        // there - it simply leaves the ranking to whatever does run one. Passed as a SUPPLIER, not resolved here:
        // the scheduler bean is initMethod="start", so pulling it while this bean is built would start its workers
        // earlier in context startup than the deployment intends, for no reason - nothing needs it until a refresh.
        return new HealthController(repositories, healthSource, maintenance::getIfAvailable);
    }

    @Bean
    public ProvenanceController provenanceController(Repositories repositories, ProvenanceSigner provenanceSigner,
                                                     AuditTrail audit) {
        return new ProvenanceController(repositories, provenanceSigner, audit);
    }

    @Bean
    public LicenseRetroController licenseRetroController(Repositories repositories, Settings settings,
                                                        Environment environment, PinnedSettings pins) {
        return new LicenseRetroController(repositories, settings, environment, pins);
    }
}
