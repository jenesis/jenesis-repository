package build.jenesis.repository.gate.wiring;

import module java.base;

import build.jenesis.repository.compliance.ComplianceSources;
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
 * <p>The binding carries the gate, the per-tenant gates a re-assessment asks, the advisory feeds, the
 * maintainer-health source, the hold-mapping dial and the meters. It reads the beans through providers resolved when a
 * publish asks, because the live configuration, the feeds and the health source are themselves built over the store
 * this binding is layered into, and naming them directly would make the store wait for beans that wait for the store.
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
                                                            ObjectProvider<ComplianceSources> sources,
                                                            ObjectProvider<MeterRegistry> meterRegistry) {
        return ComplianceScreen.binding()
                // The publishing thread's tenant (PublishTenant) picks the gate, so a tenant's own policy screens its
                // uploads and an unbound thread resolves the deployment-wide one; read on every publish, so a settings
                // change applies without a restart.
                .gate(() -> liveConfig.getObject().publishGate(PublishTenant.current()))
                // A re-assessment off any request - a scan report landing in a maintenance pass - names its tenant
                // and is answered by that tenant's publish gate.
                .tenantGates(tenant -> liveConfig.getObject().publishGate(tenant))
                // Re-queried at commit to persist an accepted coordinate's advisory findings before the next sweep.
                .advisoryFeeds(() -> sources.getObject().advisoryFeeds())
                // Probed at commit to persist an accepted coordinate's maintainer health before the next sweep.
                .healthSource(() -> sources.getObject().health())
                // Whether a failed hold-mapping round trip fails the publish or only alarms (strict-hold-mapping),
                // off by default so a broken blobs-namespace format cannot stop publishes.
                .strictHoldMapping(() -> liveConfig.getObject().strictHoldMapping())
                // Meters handed over as callbacks, so the gate module stays registry-free.
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
