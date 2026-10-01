package build.jenesis.repository.gate.wiring;

import module java.base;

import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.NamedAdvisoryFeeds;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.store.StoreBindings;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arms the publish-path compliance screen from what the deployment has configured, by binding the deployment's
 * store: every publication over a store derived from it is judged by this deployment's gate, and by no other
 * deployment's in the same process.
 *
 * <p>The binding carries the gate itself, the per-tenant gates a re-assessment asks, the advisory feeds, the
 * maintainer-health source, the hold-mapping dial and the four meters. It is separated from the beans it reads
 * (the live configuration, the feeds, the health source) because those describe a deployment and this installs a
 * screen: a deployment that installs no screen still has all of the first and none of the second. It reads them
 * through providers, resolved when a publish asks: the store this binding is layered into is what the live
 * configuration, the feeds and the health source are themselves built over, so naming them directly here would
 * make the store wait for beans that wait for the store.
 *
 * <p>Imported by the repository server through {@code ServerModuleProvider} discovery, so the server names no
 * screen and a composition without this module simply publishes unscreened.
 */
@Configuration(proxyBeanMethods = false)
public class GateWiringConfig {

    /**
     * The deployment's binding, open for the life of this context: while it is, a publish through a store that does
     * not carry it is refused rather than admitted unscreened, and closing the context retires it so the next context
     * in the same process starts clean.
     */
    @Bean(destroyMethod = "close")
    public ComplianceScreen.Binding complianceScreenBinding(ObjectProvider<LiveConfig> liveConfig,
                                                            ObjectProvider<NamedAdvisoryFeeds> namedAdvisoryFeeds,
                                                            ObjectProvider<HealthSource> healthSource,
                                                            ObjectProvider<MeterRegistry> meterRegistry) {
        return ComplianceScreen.binding()
                // The gate is resolved from the publishing thread's tenant (PublishTenant, bound by the
                // PublishTenantFilter on /repository/** and /v2/**), so a tenant's own gate policy screens its
                // uploads; an unbound thread (staging, batch, demo) resolves the deployment-wide gate. Read through
                // the live configuration on every publish, so a settings change applies without a restart.
                .gate(() -> liveConfig.getObject().publishGate(PublishTenant.current()))
                // A held artifact re-assessed off any request - a content scan's report landing in a maintenance
                // pass - has no publishing thread to resolve a tenant from, so the re-assessment names its tenant and
                // is answered by that tenant's own publish gate, the one its uploads are screened by.
                .tenantGates(tenant -> liveConfig.getObject().publishGate(tenant))
                // Re-queried at commit to persist a just-accepted coordinate's advisory findings at once, closing the
                // window between a publish and the next scheduled sweep. Restart-bound, like the feeds themselves.
                .advisoryFeeds(() -> namedAdvisoryFeeds.getObject().feeds())
                // Probed at commit to persist a just-accepted coordinate's maintainer-health into the durable ledger
                // the gate reads, closing the window between a publish and the next scheduled health sweep.
                .healthSource(healthSource::getObject)
                // Whether the publish-time hold-mapping round-trip check throws (failing the publish) or only alarms:
                // jenrepo.strict-hold-mapping, read through the live effective configuration like every gate dial.
                // Off by default, so a broken blobs-namespace format cannot stop publishes; the test config flips it.
                .strictHoldMapping(() -> liveConfig.getObject().strictHoldMapping())
                // The meters are created here in the distribution and handed over as plain callbacks, so the gate
                // module stays registry-free.
                .advisoryFeedMisses(feed -> meterRegistry.getObject()
                        .counter("jenrepo.gate.advisory.persist.miss", "feed", feed).increment())
                .verdicts((format, verdict) -> meterRegistry.getObject()
                        .counter("jenrepo.gate.verdicts", "format", format, "verdict", verdict).increment())
                .holdMappingBroken(eco -> meterRegistry.getObject()
                        .counter("jenrepo.publish.holdmapping.broken", "eco", eco).increment())
                .unparseableArtifacts(format -> meterRegistry.getObject()
                        .counter("jenrepo.gate.unparseable", "format", format).increment())
                .open();
    }

    /**
     * The binding as the deployment's store carries it: the server binds it closest to the backend, so every layer
     * above forwards it and every scope the server derives from the store carries it.
     */
    @Bean
    public StoreBindings complianceScreenStoreBindings(ComplianceScreen.Binding binding) {
        return binding.bindings();
    }
}
