package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.kernel.FirstRunHardening;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.spi.RateLimiterProvider;
import build.jenesis.repository.server.spi.TokenExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.settings.ModuleCapability;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deployment-info reads: {@code /api/config} (the store, default repository and gate posture) and
 * {@code /api/capabilities} (the installed formats, import sources, report columns and feature flags), so a client -
 * the CLI, a script, the console - renders exactly what the modules on this deployment's module path provide instead
 * of hardcoding any backend.
 */
@RestController
public class DeploymentInfoController {

    /** What the deployment carries, whatever a toggle says - a capabilities report lists a format that is
     *  installed and switched off, so a client can tell "absent" from "present but off". */
    private final List<RepositoryFormat> formats = RepositoryFormat.declared();

    private final Repositories repositories;
    private final RepositoryProperties properties;
    private final ProxyFormat.Fetcher upstreamFetcher;
    private final TokenExchange tokenExchange;
    private final AdvisorySource advisories;
    private final List<AdvisorySignal> advisorySignals;
    private final ProvenanceSigner provenanceSigner;
    private final Settings settings;
    /** The effective-value chain both reads resolve through - an operator's pin over the stored value over the
     *  deployment environment - held composed so no read can miss the pin leg. */
    private final UnaryOperator<String> effective;
    // Module presence is static for a JVM, so the discovered inputs are resolved once.
    private final boolean rateLimiting = RateLimiterProvider.resolve(key -> null) != RateLimiter.NONE;
    private final List<ImportSourceCapabilityView> importSources = ImportSourceProvider.declared().stream()
            .map(provider -> new ImportSourceCapabilityView(provider.name(), provider.label(), provider.requiresFormat()))
            .toList();
    private final boolean advisoryModule = !AdvisorySource.installed().isEmpty();

    public DeploymentInfoController(Repositories repositories, RepositoryProperties properties,
                                    ProxyFormat.Fetcher upstreamFetcher, TokenExchange tokenExchange,
                                    AdvisorySource advisories, List<AdvisorySignal> advisorySignals,
                                    ProvenanceSigner provenanceSigner, Settings settings, Environment environment,
                                    PinnedSettings pins) {
        this.repositories = repositories;
        this.properties = properties;
        this.upstreamFetcher = upstreamFetcher;
        this.tokenExchange = tokenExchange;
        this.advisories = advisories;
        this.advisorySignals = advisorySignals;
        this.provenanceSigner = provenanceSigner;
        this.settings = settings;
        this.effective = pins.effective(settings, environment);
    }

    @GetMapping("/api/config")
    @ResponseBody
    public ConfigView config() {
        // Live-editable dials, reported through the same chain LiveConfig resolves the gate through.
        boolean proxy = Boolean.parseBoolean(dial("proxy-enabled", Boolean.toString(properties.isProxyEnabled())));
        // The licence dials fall back to what their dimension declares, so the report cannot contradict the gate.
        String licenseAllowed = dial("license-allowed", declared("license-allowed"));
        String licenseUnknown = dial("license-unknown", declared("license-unknown"));
        String threshold = dial("vulnerability-threshold", properties.getVulnerabilityThreshold());
        // The advice the boot log gives, recomputed live so it falls silent once anything is configured.
        FirstRunHardening.Advice hardening = FirstRunHardening.assess(
                FirstRunHardening.firstRun(settings), DECLARED, effective);
        return new ConfigView(properties.getStore(), proxy,
                properties.isAuth(), licenseAllowed, licenseUnknown,
                threshold, advisories != AdvisorySource.none(), hardening);
    }

    /** One dial's value through {@link #effective}, or {@code fallback} when nothing in the chain sets it. */
    private String dial(String key, String fallback) {
        String resolved = effective.apply(key);
        return resolved == null ? fallback : resolved;
    }

    /** Every installed module's declared settings; what a setting declares is fixed for the JVM. */
    private static final List<Setting> DECLARED = SettingsContributor.all();

    /** A key's declared default, or empty when no installed module declares it. */
    private static String declared(String key) {
        return DECLARED.stream()
                .filter(setting -> key.equals(setting.key()))
                .map(Setting::defaultValue)
                .findFirst()
                .orElse("");
    }

    /** {@link #capabilities()} as the flat map {@link DeploymentCapabilities} contributes to
     *  {@code /api/capabilities}, one top-level key per component, recomputed per call off the live settings. */
    public Map<String, Object> capabilityMap() {
        CapabilitiesView view;
        try {
            view = capabilities();
        } catch (IOException e) {
            // Fails the contribution rather than serving a half-built body.
            throw new UncheckedIOException(e);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", view.version());
        map.put("formats", view.formats());
        map.put("importSources", view.importSources());
        map.put("signals", view.signals());
        map.put("modules", view.modules());
        map.put("features", view.features());
        return map;
    }

    /** What this deployment carries - the installed formats, import sources, report columns, modules and features.
     *  {@code version} lets a client detect a change of shape. Served through {@link #capabilityMap}. */
    public CapabilitiesView capabilities() throws IOException {
        List<FormatCapabilityView> formatViews = new ArrayList<>();
        for (RepositoryFormat format : formats) {
            formatViews.add(new FormatCapabilityView(format.name(),
                    format instanceof ArtifactLayout layout ? layout.ecosystem() : null));
        }
        List<SignalColumnView> signals = new ArrayList<>();
        for (AdvisorySignal signal : advisorySignals) {
            signals.add(new SignalColumnView(signal.name(), signal.label()));
        }
        // Enumerated from the discovered contributors; a module named only by a leftover stored document is not
        // installed.
        List<ModuleCapabilityView> moduleViews = new ArrayList<>();
        for (ModuleCapability capability : ModuleCapability.resolve(effective, settings.documents().keySet())) {
            moduleViews.add(new ModuleCapabilityView(capability.module(), capability.installed(),
                    capability.enableKey(), capability.enabled(), capability.live()));
        }
        // Optional modules contribute their own flags to the same document; these are the postures only this
        // context's beans can answer.
        return new CapabilitiesView(1, formatViews, importSources, signals, moduleViews, new FeaturesView(
                advisoryModule,
                advisories != AdvisorySource.none(),
                repositories.stagingInstalled(),
                repositories.retentionSweeper().isPresent(),
                provenanceSigner.enabled(),
                upstreamFetcher != ProxyFormat.Fetcher.NONE,
                tokenExchange != TokenExchange.NONE,
                rateLimiting,
                properties.isReadOnly()));
    }

    public record ConfigView(String store, boolean proxy, boolean auth,
                             String licenseAllowed, String licenseUnknown, String vulnerabilityThreshold,
                             boolean advisories, FirstRunHardening.Advice firstRunHardening) {
    }

    public record CapabilitiesView(int version, List<FormatCapabilityView> formats,
                                   List<ImportSourceCapabilityView> importSources,
                                   List<SignalColumnView> signals, List<ModuleCapabilityView> modules,
                                   FeaturesView features) {
    }

    /** An installed format and, when it describes coordinates, its ecosystem name. */
    public record FormatCapabilityView(String name, String ecosystem) {
    }

    /** One module's state: its JPMS name, whether it is {@code installed}, its enablement key ({@code null} for an
     *  always-on module), whether that resolves {@code enabled}, and whether a toggle applies {@code live} or on the
     *  next restart. */
    public record ModuleCapabilityView(String module, boolean installed, String enableKey, boolean enabled,
                                       boolean live) {
    }

    public record ImportSourceCapabilityView(String name, String label, boolean requiresFormat) {
    }

    /** One report column contributed by an installed {@code AdvisorySignal}. */
    public record SignalColumnView(String name, String label) {
    }

    /** The postures this context knows and no discovered contributor can see; each optional module's flag is a
     *  top-level entry contributed by that module. */
    public record FeaturesView(boolean advisories, boolean advisoriesEnabled, boolean staging, boolean retention,
                               boolean provenanceEnabled, boolean upstream, boolean tokenExchange,
                               boolean rateLimit, boolean readOnly) {
    }
}
