package build.jenesis.repository.application;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.server.kernel.FirstRunHardening;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.UnrecognisedSettings;
import build.jenesis.repository.server.DemoSeeder;
import build.jenesis.repository.server.DemoSeeding;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.PublishPathWiring;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.gateway.ProxyScreenHooks;
import build.jenesis.repository.store.ArtifactStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * The first-run boot behaviours: the background demo seeding and the boot-time advice on an unconfigured or
 * misconfigured deployment.
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoConfig.class);

    @Bean(initMethod = "start")
    public DemoSeeding demoSeeding(LiveConfig liveConfig, Settings settings, Repositories repositories,
                                   ProxyFormat.Fetcher upstreamFetcher, RepositoryProperties properties,
                                   List<PublishPathWiring> publishPathWiring, Environment environment) {
        // Off by default, and only against a completely empty artifact space: seeds the default tenant's repositories
        // in the background through the formats' own pull-through paths. publishPathWiring is never read - asking for
        // it makes the container arm the publish path before the seed publishes through it.
        ArtifactStore store = repositories.tenantScope(properties.getDefaultTenant());
        // The seeder fetches through the dispatcher, not the routed gateway, so it is handed the same DEFAULT-strength
        // proxy screen the router's fallbacks use, over the tenant's live gate resolved lazily so the demo gate config
        // applied just before the seed is the one that screens.
        PullThroughHooks demoScreen = ProxyScreenHooks.perTenant(liveConfig::proxyGate, liveConfig.holdDays(), false)
                .forTenant(properties.getDefaultTenant());
        // The seed writes, so a read-only deployment does not seed.
        return new DemoSeeding(liveConfig.demo() && !properties.isReadOnly(),
                new DemoSeeder(ServingConfig.enabledFormats(environment), upstreamFetcher, demoScreen), store,
                () -> applyDemoGateConfig(settings, liveConfig));
    }

    /**
     * On a fresh deployment (no runtime configuration stored), logs once at INFO the dials that still need a
     * deployment-specific answer. It writes nothing and falls silent once anything is configured;
     * {@link DeploymentInfoController} serves the same {@link FirstRunHardening#assess advice} on {@code /api/config}.
     */
    @Bean
    public FirstRunHardening.Advice firstRunHardening(Settings settings, Environment environment,
                                                     PinnedSettings pins) {
        // The chain /api/config resolves through, so the boot log and the live read agree.
        UnaryOperator<String> effective = pins.effective(settings, environment);
        FirstRunHardening.Advice advice = FirstRunHardening.assess(
                FirstRunHardening.firstRun(settings), SettingsContributor.all(), effective);
        if (advice.hasGuidance()) {
            // One line naming the dials; their descriptions are on the setup guide and on /api/config.
            LOGGER.info("FIRST-RUN HARDENING: this deployment has no runtime configuration yet. Authorization is on "
                    + "and the gate refuses CRITICAL findings; the advisory feeds are off until switched on. The "
                    + "console's setup guide (/ui/setup) walks through the settings that still need a "
                    + "deployment-specific answer: {}. This notice stops once you configure anything.",
                    advice.dimensions().stream().map(FirstRunHardening.Step::key)
                            .collect(Collectors.joining(", ")));
        }
        return advice;
    }

    /**
     * Warn once, at boot, about any {@code jenrepo.*} property this deployment does not read.
     *
     * <p>Settings change by clean cutover and an unrecognised key is silently ignored, so a renamed key would
     * otherwise stop having effect with nothing saying so. It warns rather than refuses: failing a start over a stale
     * key is the worse trade.
     *
     * <p>Recognised is computed, never listed: the settings catalogue (module toggles included), every
     * {@code @ConfigurationProperties} object bound under the namespace, and the prefix of each {@code Map}-bound
     * property, so {@code jenrepo.proxy.<format>} is known.
     */
    @Bean
    public UnrecognisedSettings.Report unrecognisedSettings(ConfigurableEnvironment environment,
                                                           ApplicationContext context) {
        List<UnrecognisedSettings.Bound> bound = new ArrayList<>();
        ConfigurationPropertiesBean.getAll(context).values().forEach(each -> {
            String prefix = each.getAnnotation().prefix();
            if (prefix.startsWith("jenrepo") && each.getInstance() != null) {
                bound.add(new UnrecognisedSettings.Bound(prefix, each.getInstance()));
            }
        });
        Set<String> configured = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.regionMatches(true, 0, "jenrepo", 0, "jenrepo".length())) {
                        configured.add(name);
                    }
                }
            }
        }
        Set<String> declared = new HashSet<>(ArtifactStoreProvider.declaredConfig());
        declared.addAll(SettingsContributor.allStartupKeys());
        UnrecognisedSettings.Report report = UnrecognisedSettings.assess(configured,
                UnrecognisedSettings.known(SettingsContributor.all(), bound, declared));
        if (!report.isEmpty()) {
            StringBuilder message = new StringBuilder("UNRECOGNISED SETTINGS: this deployment sets "
                    + report.findings().size() + " jenrepo.* propert"
                    + (report.findings().size() == 1 ? "y" : "ies") + " that nothing reads. Their values have no "
                    + "effect - most often a key renamed by a release, since settings change here by cutover rather "
                    + "than by keeping the old spelling alive.");
            for (UnrecognisedSettings.Finding finding : report.findings()) {
                message.append(System.lineSeparator()).append("  - ").append(finding.key());
                if (finding.nearest() != null) {
                    message.append(" (did you mean ").append(finding.nearest()).append("?)");
                }
            }
            LOGGER.warn(message.toString());
        }
        return report;
    }

    /** Sets the demo gate config - a version floor quarantining the old log4j-core and a deny-list rejecting
     *  commons-collections, so the review surfaces carry examples - on each dial an operator left unset, then rebuilds
     *  the live gate. {@link DemoSeeding} runs it only when a seed is about to happen. */
    private static void applyDemoGateConfig(Settings settings, LiveConfig liveConfig) {
        try {
            setIfBlank(settings, "version-floor", "org.apache.logging.log4j:log4j-core >= 2.17.0");
            setIfBlank(settings, "version-floor-action", "QUARANTINE");
            setIfBlank(settings, "deny-list", "commons-collections:commons-collections");
            liveConfig.rebuild();
        } catch (IOException exception) {
            LOGGER.warn("Could not apply the demo gate config", exception);
        }
    }

    private static void setIfBlank(Settings settings, String key, String value) throws IOException {
        if (settings.getOrDefault(key, "").isBlank()) {
            settings.set(key, value);
        }
    }
}
