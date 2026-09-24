package build.jenesis.repository.gate.wiring;

import module java.base;

import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.NamedAdvisoryFeeds;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PublishTenant;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arms the publish-path compliance screen from what the deployment has configured, and unwires it when the
 * context closes.
 *
 * <p>Each bean here reaches {@code ComplianceScreen}'s static wiring - the gate itself, the advisory feeds,
 * the feed-miss and verdict meters, the hold mapping and the unparseable counter, the maintainer-health
 * source. They are separated from the beans they read (the live configuration, the feeds, the health source)
 * because those describe a deployment and these install a screen: a deployment that installs no screen still
 * has all of the first and none of the second.
 *
 * <p>Imported by the repository server through {@code ServerModuleProvider} discovery, so the server names no
 * screen and a composition without this module simply publishes unscreened.
 */
@Configuration(proxyBeanMethods = false)
public class GateWiringConfig {

    @Bean(destroyMethod = "close")
    public AutoCloseable complianceScreenHealthWiring(HealthSource healthSource) {
        // The publish screen probes this live health source at commit to persist a just-accepted coordinate's
        // maintainer-health into the durable ledger the gate reads, closing the window between a publish and
        // the next scheduled health sweep. Restart-bound like the feeds (a constant supplier); the wiring is unwired
        // when this context closes so the next context in the same JVM starts clean.
        return ComplianceScreen.healthSource(() -> healthSource);
    }

    @Bean(destroyMethod = "close")
    public ComplianceScreen.Wiring complianceScreenWiring(LiveConfig liveConfig) {
        // The publish-path compliance gate rides the publication-interceptor chain as the discovered
        // ComplianceScreen; wiring the live gate here - and unwiring it when this context closes - keeps every
        // publication in this JVM screened by this deployment's runtime-tunable policy without the serving
        // path knowing the gate exists. The gate is resolved from the publishing thread's tenant
        // (PublishTenant, bound by the PublishTenantFilter on /repository/** and /v2/**), so a tenant's own gate policy
        // screens its uploads; an unbound thread (staging, batch, demo) resolves the deployment-wide gate.
        return ComplianceScreen.live(() -> liveConfig.publishGate(PublishTenant.current()));
    }

    @Bean(destroyMethod = "close")
    public AutoCloseable complianceScreenFeedsWiring(NamedAdvisoryFeeds namedAdvisoryFeeds) {
        // The publish screen re-queries these named feeds at commit to persist a just-accepted coordinate's advisory
        // findings at once, closing the window between a publish and the next scheduled sweep. The feeds are
        // restart-bound (unlike the runtime-tunable gate policy), so a constant supplier of the boot-built map; the
        // wiring is unwired when this context closes so the next context in the same JVM starts clean.
        return ComplianceScreen.advisoryFeeds(namedAdvisoryFeeds::feeds);
    }

    @Bean(destroyMethod = "close")
    public AutoCloseable complianceScreenFeedMissesWiring(MeterRegistry meterRegistry) {
        // Count a commit-time feed re-query the warm cache could not answer (a feed failing closed with
        // nothing cached) as jenreg.gate.advisory.persist.miss tagged by feed - the gate module hands the count
        // through a plain callback and stays registry-free, the Micrometer counter is created here in the distribution.
        return ComplianceScreen.advisoryFeedMisses(feed ->
                meterRegistry.counter("jenreg.gate.advisory.persist.miss", "feed", feed).increment());
    }

    @Bean(destroyMethod = "close")
    public AutoCloseable gateVerdictsWiring(MeterRegistry meterRegistry) {
        // Count every committed gate verdict, on EVERY publish path (deploy, staging, batch), as jenreg.gate.verdicts
        // tagged by format and verdict - the gate module stays registry-free by handing the count through a plain
        // callback, and the Micrometer counter is created here in the distribution.
        return ComplianceScreen.verdicts((format, verdict) ->
                meterRegistry.counter("jenreg.gate.verdicts", "format", format, "verdict", verdict).increment());
    }

    @Bean(destroyMethod = "close")
    public AutoCloseable complianceScreenStrictHoldMappingWiring(LiveConfig liveConfig) {
        // CEP-P2 (C1-A2): whether the publish-time hold-mapping round-trip check throws (failing the publish) or only
        // alarms is jenreg.strict-hold-mapping, read through the same live effective config the gate dials
        // are, so it applies on the next settings re-read like every other dial. Off by default (production stays
        // alarm-not-abort - a broken blobs-namespace format must not DoS publishes); the test config flips it on.
        return ComplianceScreen.strictHoldMapping(liveConfig::strictHoldMapping);
    }

    @Bean(destroyMethod = "close")
    public AutoCloseable complianceScreenHoldMappingBrokenWiring(MeterRegistry meterRegistry) {
        // Count a publish whose blobs-namespace format resolves no served path or content hash for the artifact it just
        // laid out as jenreg.publish.holdmapping.broken tagged by ecosystem - the publish-time sibling of the sweep's
        // jenreg.vulnerabilities.hold.unenforceable gauge, the production backstop even when strict mode is off.
        // Registry-free like the verdicts meter: the gate module hands the count through a plain callback, the
        // Micrometer counter is created here in the distribution.
        return ComplianceScreen.holdMappingBroken(eco ->
                meterRegistry.counter("jenreg.publish.holdmapping.broken", "eco", eco).increment());
    }

    @Bean(destroyMethod = "close")
    public AutoCloseable complianceScreenUnparseableWiring(MeterRegistry meterRegistry) {
        // Count every artifact an inspector could not parse as jenreg.gate.unparseable tagged by format - the §9
        // make-errors-visible diagnostic beside the WARNING the screen logs. Registry-free like the verdicts meter:
        // the gate module hands the count through a plain callback, the Micrometer counter is created here.
        return ComplianceScreen.unparseableArtifacts(format ->
                meterRegistry.counter("jenreg.gate.unparseable", "format", format).increment());
    }
}
