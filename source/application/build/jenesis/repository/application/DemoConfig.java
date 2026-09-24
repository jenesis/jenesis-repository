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
 * The first-run boot behaviours split out of {@link RepositoryConfig}: the background demo seeding (off by
 * default, only against a completely empty artifact space, screened by the same DEFAULT-strength proxy screen the
 * router uses) and the first-run guided-hardening advice logged once on a genuinely fresh deploy. Every bean is
 * copied verbatim from the former monolith; the split is behaviour-preserving. The demo seed depends on every
 * {@link PublishPathWiring} bean by parameter, so whatever arms the publish path is armed before it publishes. The
 * enabled-format list is shared with {@link ServingConfig#enabledFormats}.
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoConfig.class);

    @Bean(initMethod = "start")
    public DemoSeeding demoSeeding(LiveConfig liveConfig, Settings settings, Repositories repositories,
                                   ProxyFormat.Fetcher upstreamFetcher, RepositoryProperties properties,
                                   List<PublishPathWiring> publishPathWiring, Environment environment) {
        // Demo mode seeds a fresh, empty repository with real artifacts through the formats' own pull-through paths,
        // on a background thread after boot (never blocking it) and only against a completely empty artifact space;
        // off by default. It seeds the default tenant's repositories, one per format, and - only when it is about to seed
        // - applies a small demo gate config (a version floor that quarantines the old log4j-core, a deny-list that
        // rejects commons-collections) so the QUARANTINE/REJECT surfaces carry examples. Depending on the compliance
        // publish-path wiring keeps whatever screens a publish armed before the seed publishes through it. The list
        // is never read - asking for it is the whole point, because the container builds it first.
        ArtifactStore store = repositories.tenantScope(properties.getDefaultTenant());
        // #79: the demo proxy leg is dispatcher-direct - it does NOT pass through the routed gateway's own
        // screening() decoration - so since EPIC 26 demoted the embedded per-format publish screen it pulled through
        // unscreened. Hand the free DemoSeeder a PullThroughHooks whose screenFetch is the SAME DEFAULT-strength
        // ProxyScreen the router's DEFAULT fallbacks use, over the serving tenant's live gate (resolved lazily, so the
        // demo gate config armed just before the seed is the one that screens) and the seed target store - so the demo
        // proxy leg is screened by the identical mechanism, with no screening code added to the free DemoSeeder and no
        // reintroduced embedded publish screen. A quarantined/rejected suggestion then populates the QUARANTINE/REJECT
        // surface from the proxy-leg screen.
        ProxyScreenHooks demoScreen = new ProxyScreenHooks(
                () -> liveConfig.proxyGate(properties.getDefaultTenant()), liveConfig.holdDays());
        // The seed writes through the store, so a read-only deployment runs no seeding - a background write job.
        return new DemoSeeding(liveConfig.demo() && !properties.isReadOnly(),
                new DemoSeeder(ServingConfig.enabledFormats(environment), upstreamFetcher, demoScreen), store,
                () -> applyDemoGateConfig(settings, liveConfig));
    }

    /**
     * The first-run guided-hardening step (audit P4): on a genuinely fresh deploy (no runtime configuration persisted),
     * log once the deployment-specific dials still at their open default so an operator sees them on first boot -
     * mirroring the loud auth-disabled boot warning, but at INFO since the secure floor is already active and this is
     * advisory guidance, not an insecure posture. Reads the store-backed {@link Settings} the same way the demo seeder
     * reads an empty artifact space, and writes nothing (the dials need a deployment-specific answer there is no safe
     * universal value for). The advice falls silent once anything is configured, so a configured deploy is never
     * re-nagged; {@link DeploymentInfoController} recomputes the same {@link FirstRunHardening#assess advice} live on
     * {@code /api/config} so the console/CLI can render and act on it. Returned as a bean so the boot computation is a
     * first-class, testable wiring rather than a side effect buried in another bean.
     */
    @Bean
    public FirstRunHardening.Advice firstRunHardening(Settings settings, Environment environment,
                                                     PinnedSettings pins) {
        // The same pin > stored > deployment chain /api/config recomputes this advice through, so the boot log and the
        // live read cannot disagree about which dials are still open.
        UnaryOperator<String> effective = pins.effective(settings, environment);
        FirstRunHardening.Advice advice = FirstRunHardening.assess(
                FirstRunHardening.firstRun(settings), SettingsContributor.all(), effective);
        if (advice.hasGuidance()) {
            StringBuilder message = new StringBuilder("FIRST-RUN HARDENING: this deployment has no runtime "
                    + "configuration yet. The secure floor is active (authorization on, CVSS gate CRITICAL, public "
                    + "advisory feeds on, a rate ceiling and a short immaturity hold); tighten the deployment-specific "
                    + "dials below over /api/settings, the console or the CLI. This notice stops once you configure "
                    + "anything.");
            for (FirstRunHardening.Step step : advice.dimensions()) {
                message.append(System.lineSeparator()).append("  - ").append(step.key())
                        .append(" (").append(step.label()).append("): ").append(step.detail());
            }
            for (String next : advice.nextSteps()) {
                message.append(System.lineSeparator()).append("  - ").append(next);
            }
            LOGGER.info(message.toString());
        }
        return advice;
    }

    /**
     * Warn once, at boot, about any {@code jenreg.*} property this deployment does not read.
     *
     * <p>The gap it closes: settings change by clean cutover here - compatibility shims are disallowed - while an
     * unrecognised key is silently ignored, so a rename leaves whoever had the old key set with a value that quietly
     * stops having effect and nothing anywhere saying so. This is the saying-so, in {@code Features.active}'s
     * register: a warning naming the keys, never a refusal, because failing a start over a stale key would be a far
     * worse trade than the silence it replaces.
     *
     * <p>Recognised is <em>computed</em>, never listed. The catalogue supplies the runtime-editable dials (the
     * per-module toggles among them, generated from the installed modules and therefore invisible to the build's
     * class-file extractor but perfectly visible here); every {@code @ConfigurationProperties} object bound under the
     * namespace supplies the boot-only properties the catalogue deliberately omits, which are most of a real
     * deployment's configuration; and a {@code Map}-bound property opens its prefix, which is what keeps
     * {@code jenreg.proxy.<format>} from being reported as unknown without anyone writing that exception down.
     *
     * <p>Returned as a bean for the reason the hardening advice is: the boot computation is then first-class and
     * testable wiring rather than a side effect buried in another bean.
     */
    @Bean
    public UnrecognisedSettings.Report unrecognisedSettings(ConfigurableEnvironment environment,
                                                           ApplicationContext context) {
        List<UnrecognisedSettings.Bound> bound = new ArrayList<>();
        ConfigurationPropertiesBean.getAll(context).values().forEach(each -> {
            String prefix = each.getAnnotation().prefix();
            if (prefix.startsWith("jenreg") && each.getInstance() != null) {
                bound.add(new UnrecognisedSettings.Bound(prefix, each.getInstance()));
            }
        });
        Set<String> configured = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.regionMatches(true, 0, "jenreg", 0, "jenreg".length())) {
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
                    + report.findings().size() + " jenreg.* propert"
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

    /** Layer in the demo gate config only where an operator left the dial unset, then rebuild the live gate so the
     *  seed publishes through it - never clobbering a real gate policy, and a no-op outside the empty-repo demo path
     *  (this runs from {@link DemoSeeding} only when a seed is actually about to happen). */
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
