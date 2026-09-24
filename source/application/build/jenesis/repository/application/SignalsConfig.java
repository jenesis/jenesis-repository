package build.jenesis.repository.application;

import build.jenesis.repository.compliance.ComplianceSettings;
import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
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
import build.jenesis.repository.gateway.LiveDefinitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * What a deployment configures for the compliance dimensions, split out of {@link RepositoryConfig}: the
 * attributed advisory feeds built once and shared, the de-duplicated {@link AdvisorySource} read from them, the
 * maintainer-health source, the report signals and provenance signer, and the live {@link LiveConfig} (with the
 * tenant-overlaid VEX view and the boot-time definition sweep).
 *
 * <p>What ARMS a publish screen with any of this is not here: a screen is installed by whichever module
 * provides one, and arms itself from these beans through its own configuration. Every bean is copied from the former
 * monolith; the split is behaviour-preserving.
 */
@Configuration(proxyBeanMethods = false)
public class SignalsConfig {

    @Bean(destroyMethod = "close")
    public SignalContext.Deployment signalSnapshots(ArtifactStore store) {
        // The one wiring point for the signal family's durable storage. A signal source is a deployment
        // singleton - the CISA catalogue is the same public data for every tenant - so its snapshots are
        // deployment-global, and this is the only place in the process where the *root* store is in scope: every site
        // that resolves feeds (a maintenance pass, a console view, a gate dimension) holds at most a tenant- or
        // repository-scoped view, which is exactly why SignalSourceProvider.create takes no store from its caller.
        // Each provider then sees this root narrowed to config/signals/<its name> and nothing else. Unwired when this
        // context closes so the next context in the same JVM starts clean.
        return SignalContext.deployment(store, Clock.systemUTC());
    }

    @Bean
    public NamedAdvisoryFeeds namedAdvisoryFeeds(Environment environment, SignalContext.Deployment signalSnapshots) {
        // Feeds are discovered plugins (osv, github, ...); each reads its own enable/endpoint from the effective
        // config (settings layered over the product defaults), so the composition names no feed. Built once here so the
        // gate's de-duplicated union and the publish screen's per-feed commit-time re-query share the same feed
        // instances - and therefore the same warm FeedCache the gate's assess just populated. The snapshot binding is
        // a parameter rather than an ordering annotation, so a feed created here already sees its durable space.
        return new NamedAdvisoryFeeds(AdvisorySource.named(Features.namespaced(environment::getProperty)));
    }

    @Bean
    public AdvisorySource advisorySource(NamedAdvisoryFeeds namedAdvisoryFeeds) {
        // The gate's single de-duplicated view over the same feed instances the screen re-queries at commit, so a feed
        // the gate warmed answers the screen's re-query without a second upstream call.
        return AdvisorySource.resolve(namedAdvisoryFeeds.feeds().values());
    }

    @Bean
    public HealthSource healthSource(Environment environment, SignalContext.Deployment signalSnapshots) {
        // The maintainer-health source is a discovered plugin (scorecard/deps.dev); it reads its own enable/endpoint
        // from the effective config, so the composition names no source. Built once here and shared: the gate reads the
        // durable health ledger, but the publish screen's commit-time persistence probes THIS live source and the
        // /api/health refresh re-probes it - the same instance, so its FeedCache is shared. HealthSource.none() when no
        // source is enabled, which leaves the screen writing no health and the endpoint refresh a no-op.
        return HealthSource.resolve(Features.namespaced(environment::getProperty));
    }

    @Bean
    public List<AdvisorySignal> advisorySignals(Environment environment, SignalContext.Deployment signalSnapshots) {
        // Report columns are discovered plugins (known-exploited, epss, ...); each reads its own enable/endpoint
        // from the effective config, so the report names no signal.
        return AdvisorySignal.resolve(Features.namespaced(environment::getProperty));
    }

    @Bean
    public ProvenanceSigner provenanceSigner(Environment environment) {
        // Signers are discovered plugins (the DSSE signer, ...); each reads its own key configuration from the
        // effective config, so the composition names no implementation.
        return ProvenanceSignerProvider.resolve(Features.namespaced(environment::getProperty));
    }

    @Bean
    public LiveConfig liveConfig(Settings settings, RepositoryProperties properties, AdvisorySource advisories,
                                 Environment environment, PinnedSettings pinnedSettings, ArtifactStore store,
                                 SignalContext.Deployment signalSnapshots) {
        // The gate's dimensions are discovered plugins (licenses, known-exploited, ...); the lookup answers their
        // keys from the deployment's file/env configuration, under the runtime settings LiveConfig layers on top -
        // except where an operator has pinned a key from above the store, which the pin probe reports.
        LiveConfig liveConfig = new LiveConfig(settings, properties, advisories,
                Features.namespaced(environment::getProperty),
                key -> pinnedSettings.pinned(key).map(PinnedSettings.Pin::value));
        // The store-backed VEX source, overlaid per tenant on the publish- and proxy-path gates: a tenant's ingested
        // OpenVEX / CSAF statements suppress a non-applicable advisory on its own uploads and pull-throughs. VEX is a
        // ServiceLoader-discovered capability (the vex plugin provides VexProvider), so the neutral server names no
        // VexStore: it resolves the single installed provider once - a Vex.NONE-yielding fallback when the plugin is
        // absent, so a deployment without it still boots and screens - and hands it the tenant per gate resolution. The
        // provider owns the vex feature gate and the fail-toward-screening fallback (no suppression on off / unreadable).
        VexProvider vexProvider = VexProvider.resolve();
        liveConfig.vex(tenant -> tenantVex(vexProvider, store, pinnedSettings.effective(settings, environment),
                properties, tenant));
        // EPIC 25 §9 boot-time definition sweep: parse every configured repository definition up front and fail the
        // boot LOUD (naming the repository and the remedy) on a broken one, rather than booting with it silently
        // ignored while the repository serves the deployment's default path - the store=s3-with-the-module-off posture
        // applied to repository definitions. A valid definition carrying only a warn condition (unscreened / mixed
        // strength) logs its warning but does not block the boot.
        // The redirect serve tokens parse exactly when an installed redirect plane module serves them; registered here,
        // before the sweep, so a `fallback <url> redirect` definition is not refused at boot by a flag the router
        // construction (which runs later) would have set.
        return liveConfig;
    }

    /**
     * The router's live definitions, swept at boot. The sweep runs here, on the bean the router and the kernel's
     * resolver both take, so it runs exactly once and before either exists; the redirect serve tokens are registered
     * first, so a {@code fallback <url> redirect} definition is not refused at boot by a flag the router construction
     * (which runs later) would have set.
     */
    @Bean
    public LiveDefinitions liveDefinitions(LiveConfig liveConfig, Settings settings, RepositoryProperties properties) {
        LiveDefinitions definitions = new LiveDefinitions(liveConfig, settings, properties);
        ServingConfig.registerRedirectTokens();
        definitions.sweepDefinitions();
        return definitions;
    }

    /** A tenant's ingested VEX statements as the gate's {@link Vex} view, through the discovered {@link VexProvider}:
     *  {@link Vex#NONE} when the tenant name is unusable, and (in the provider) when the feature is off or the store
     *  read fails - fail toward screening. The tenant is resolved to the default tenant when the publishing thread
     *  carries none (a single-tenant deployment), so its one tenant's VEX applies; the provider reads its feature
     *  toggle through the effective-settings lookup - an operator's pin over the stored settings over the deployment
     *  configuration, the same chain the gate around it resolves through. */
    private static Vex tenantVex(VexProvider vexProvider, ArtifactStore store, UnaryOperator<String> effective,
                                 RepositoryProperties properties, String tenant) {
        String resolved = tenant == null || tenant.isBlank() ? properties.getDefaultTenant() : tenant;
        if (resolved == null || !Repositories.valid(resolved)) {
            return Vex.NONE;
        }
        return vexProvider.over(resolved, store, effective);
    }

    /**
     * Wire the effective per-tenant settings into the screens, so an inspector that verifies signatures reads the
     * keys an operator configured at runtime rather than the ones this process booted with. It is the same lookup
     * {@link LiveConfig} builds the gate's dimensions from, resolved per publish against the publishing tenant.
     */
    @Bean(destroyMethod = "close")
    public AutoCloseable complianceSettingsWiring(LiveConfig liveConfig) {
        return ComplianceSettings.wire(() -> liveConfig.settings(PublishTenant.current()));
    }

}
