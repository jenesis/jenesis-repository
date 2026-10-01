package build.jenesis.repository.application;

import build.jenesis.repository.compliance.ComplianceSettings;
import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.NamedAdvisoryFeeds;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.compliance.ProvenanceSignerProvider;
import build.jenesis.repository.compliance.Vex;
import build.jenesis.repository.compliance.VexProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoreBindings;
import org.springframework.beans.factory.ObjectProvider;
import build.jenesis.repository.gateway.LiveDefinitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The compliance dimensions' inputs: the advisory feeds built once and shared, the de-duplicated
 * {@link AdvisorySource} over them, the maintainer-health source, the report signals, the provenance signer and the
 * {@link LiveConfig} gate with its per-tenant VEX view. A publish screen is armed by the module that provides it,
 * from these beans.
 */
@Configuration(proxyBeanMethods = false)
public class SignalsConfig {

    @Bean(destroyMethod = "close")
    public SignalContext.Deployment signalSnapshots(ArtifactStore store) {
        // Signal snapshots are deployment-global, and this is the one place the root store is in scope, which is why
        // SignalSourceProvider.create takes no store: each provider sees config/signals/<its name>. Unwired when the
        // context closes, so the next context in the JVM starts clean. Beans that resolve signal sources take it as
        // an unread parameter, so the binding exists before any source is created.
        return SignalContext.deployment(store, Clock.systemUTC());
    }

    @Bean
    public NamedAdvisoryFeeds namedAdvisoryFeeds(Environment environment, SignalContext.Deployment signalSnapshots) {
        // Built once, so the gate's union and the screen's commit-time re-query share instances and their warm cache.
        return new NamedAdvisoryFeeds(AdvisorySource.named(Features.namespaced(environment::getProperty)));
    }

    @Bean
    public AdvisorySource advisorySource(NamedAdvisoryFeeds namedAdvisoryFeeds) {
        return AdvisorySource.resolve(namedAdvisoryFeeds.feeds().values());
    }

    @Bean
    public HealthSource healthSource(Environment environment, SignalContext.Deployment signalSnapshots) {
        // Built once and shared by the screen's commit-time probe and the /api/health refresh; HealthSource.none()
        // when no source is enabled.
        return HealthSource.resolve(Features.namespaced(environment::getProperty));
    }

    @Bean
    public List<AdvisorySignal> advisorySignals(Environment environment, SignalContext.Deployment signalSnapshots) {
        return AdvisorySignal.resolve(Features.namespaced(environment::getProperty));
    }

    @Bean
    public ProvenanceSigner provenanceSigner(Environment environment) {
        return ProvenanceSignerProvider.resolve(Features.namespaced(environment::getProperty));
    }

    @Bean
    public LiveConfig liveConfig(Settings settings, RepositoryProperties properties, AdvisorySource advisories,
                                 Environment environment, PinnedSettings pinnedSettings, ArtifactStore store,
                                 SignalContext.Deployment signalSnapshots) {
        // Dimension keys resolve from the runtime settings over the deployment's configuration, except a key pinned
        // above the store.
        LiveConfig liveConfig = new LiveConfig(settings, properties, advisories,
                Features.namespaced(environment::getProperty),
                key -> pinnedSettings.pinned(key).map(PinnedSettings.Pin::value));
        // A tenant's ingested VEX statements suppress a non-applicable advisory on its own uploads and pull-throughs;
        // with no provider installed the view is Vex.NONE.
        VexProvider vexProvider = VexProvider.resolve();
        liveConfig.vex(tenant -> tenantVex(vexProvider, store, pinnedSettings.effective(settings, environment),
                properties, tenant));
        return liveConfig;
    }

    /** The one place a setting is changed, called by the API's handlers and in process by the console. It resolves a
     *  candidate against this node's {@link LiveConfig} and writes through its {@link Settings}, so it applies at
     *  once. */
    @Bean
    public SettingsEditor settingsEditor(Settings settings, PinnedSettings pinnedSettings, LiveConfig liveConfig,
                                         AuditTrail auditTrail) {
        return new SettingsEditor(settings, pinnedSettings::pinned, liveConfig, auditTrail);
    }

    /**
     * The router's live definitions, swept at boot on the bean the router and the kernel's resolver both take, so
     * the sweep runs once and before either exists. A broken definition fails the boot naming the repository; a
     * warning condition only logs. The redirect tokens are registered first, so a redirect definition parses.
     */
    @Bean
    public LiveDefinitions liveDefinitions(LiveConfig liveConfig, Settings settings, RepositoryProperties properties) {
        LiveDefinitions definitions = new LiveDefinitions(liveConfig, settings, properties);
        ServingConfig.registerRedirectTokens();
        definitions.sweepDefinitions();
        return definitions;
    }

    /** A tenant's VEX view, the default tenant's when the thread carries none; {@link Vex#NONE} when the name is
     *  unusable, and in the provider when the feature is off or the read fails - failing toward screening. */
    private static Vex tenantVex(VexProvider vexProvider, ArtifactStore store, UnaryOperator<String> effective,
                                 RepositoryProperties properties, String tenant) {
        String resolved = tenant == null || tenant.isBlank() ? properties.getDefaultTenant() : tenant;
        if (resolved == null || !Repositories.valid(resolved)) {
            return Vex.NONE;
        }
        return vexProvider.over(resolved, store, effective);
    }

    /**
     * Binds the publishing tenant's effective settings to the store, so a signature inspector reads the keys
     * configured at runtime. Resolved through a provider because {@link LiveConfig} is itself built over this store.
     */
    @Bean
    public StoreBindings complianceSettingsStoreBindings(ObjectProvider<LiveConfig> liveConfig) {
        return ComplianceSettings.bindings(() -> liveConfig.getObject().settings(PublishTenant.current()));
    }

}
