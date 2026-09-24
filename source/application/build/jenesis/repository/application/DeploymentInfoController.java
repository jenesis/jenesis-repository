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
 * of hardcoding any backend. One of the focused controllers the {@code RepositoryController}
 * monolith split into.
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
     *  deployment environment. Held as the composed lookup rather than the probe, so neither read can be
     *  written with a leg missing: a pin-blind read reports a stored value the running server does not use. */
    private final UnaryOperator<String> effective;
    // Module presence is static for a JVM; resolved once so the capability flag reflects the installed metering.
    private final boolean rateLimiting = RateLimiterProvider.resolve(key -> null) != RateLimiter.NONE;
    // The remaining discovered inputs are equally static for the JVM, so they are resolved once here rather than
    // re-scanning the ServiceLoader on every /api/capabilities call.
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
        // bridge this Spring bean's live rich-capabilities view to the free-core CapabilityContributor SPI, which
        // is ServiceLoader-discovered (no Spring context) inside the free RepositoryController.capabilities(). The free
        // controller now serves the ONE /api/capabilities, merging this contribution onto its base map - retiring the
        // WebMvcRegistrations mapping-suppression stopgap that dropped the capabilities mapping so this controller
        // could own the path. Installed last, after every field is assigned, so the supplier reads a fully-built bean.
        DeploymentCapabilities.install(this::capabilityMap);
    }

    @GetMapping("/api/config")
    @ResponseBody
    public ConfigView config() {
        // The runtime-tunable dials (proxy, licence policy, vulnerability threshold) are editable live over
        // /api/settings and applied to the gate without a restart, so report their effective value through the same
        // pin > stored > deployment chain LiveConfig resolves the gate through - reading the file default straight off
        // RepositoryProperties would show a stale posture until reboot, and reading the store alone would report a
        // stored value an operator's pin above it makes inert.
        boolean proxy = Boolean.parseBoolean(dial("proxy-enabled", Boolean.toString(properties.isProxyEnabled())));
        // The licence dials fall back to what their dimension DECLARES, not to a field here. Licence is a discovered
        // plugin, so this class never held its defaults for any purpose but this report - and when the dimension's
        // default moved and the field did not, this is the line that told an operator the product holds an undeclared
        // licence while the gate was serving it. Reading the catalogue means the report cannot say anything the
        // dimension does not.
        String licenseAllowed = dial("license-allowed", declared("license-allowed"));
        String licenseUnknown = dial("license-unknown", declared("license-unknown"));
        String threshold = dial("vulnerability-threshold", properties.getVulnerabilityThreshold());
        // The first-run guided-hardening advice (audit P4), recomputed live from the stored settings so it renders the
        // current posture and falls silent the moment anything is configured - a read that renders durable state, never
        // a write (§10). The same FirstRunHardening.assess the boot log uses, so both surfaces agree.
        FirstRunHardening.Advice hardening = FirstRunHardening.assess(
                FirstRunHardening.firstRun(settings), DECLARED, effective);
        return new ConfigView(properties.getStore(), proxy,
                properties.isAuth(), licenseAllowed, licenseUnknown,
                threshold, advisories != AdvisorySource.none(), hardening);
    }

    /** One runtime-tunable dial's effective value: the {@link #effective} chain (an operator's pin over the stored
     *  value over the deployment environment), falling back to the given default when nothing in that chain sets the
     *  key - the same order {@code LiveConfig} resolves the running gate through. */
    private String dial(String key, String fallback) {
        String resolved = effective.apply(key);
        return resolved == null ? fallback : resolved;
    }

    /** A discovered dimension's own declared default for a key - the value its settings screen row shows and its
     *  policy applies, read from the one catalogue rather than copied into a field here. Empty when no installed
     *  module declares the key, which is the honest answer for a dimension this deployment does not carry. */
    /** Every installed module's declared settings, read once: this controller consulted the catalogue three
     *  times per request - twice through {@link #declared} and once for the hardening advice - and each call was a
     *  walk of the module graph's service declarations. What a setting *declares* is fixed for the JVM; what a
     *  deployment has *set* is read live, below, and is the part that must be. */
    private static final List<Setting> DECLARED = SettingsContributor.all();

    private static String declared(String key) {
        return DECLARED.stream()
                .filter(setting -> key.equals(setting.key()))
                .map(Setting::defaultValue)
                .findFirst()
                .orElse("");
    }

    /** The rich-capabilities view as the flat, JSON-serialisable map the
     *  {@link build.jenesis.repository.server.spi.CapabilityContributor} merges onto {@code /api/capabilities}: the six
     *  {@link CapabilitiesView} components ({@code version}, {@code formats}, {@code importSources}, {@code signals},
     *  {@code modules}, {@code features}) as top-level keys, preserving the exact shape the endpoint served before the
     *  free controller took ownership of the mapping - the base keys ({@code readOnly}, {@code auth},
     *  {@code anonymousRights}) are then added around it by the merge, a strict superset of the old body. Recomputed
     *  per call off the live settings, so a toggle re-resolves without a restart. */
    public Map<String, Object> capabilityMap() {
        CapabilitiesView view;
        try {
            view = capabilities();
        } catch (IOException e) {
            // The rich view reads the stored-settings documents; a read failure there surfaces as an unchecked error so
            // the merge (and the capabilities() the SPI feeds) fails cleanly rather than serving a half-built body.
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

    /** What this deployment carries - the installed formats, import sources, report columns and features - so a
     *  client (the CLI, a script) renders exactly what the modules on this module path provide instead of
     *  hardcoding any backend. {@code version} lets a future shape change be detected. No longer mapped to
     *  {@code /api/capabilities} directly: the {@code RepositoryController} owns the single mapping and
     *  merges this view through the {@code CapabilityContributor} SPI ({@link #capabilityMap}), so there is no
     *  cross-layer mapping override. */
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
        // The per-module capability list, enumerated from the discovered settings contributors and provider registries
        // rather than a maintained table: each module's installed and enabled state (the enablement gate's effective
        // value, an operator's pin over the store over the product default) and whether a toggle applies live or on the
        // next restart. A module named only by a leftover stored document renders not-installed.
        List<ModuleCapabilityView> moduleViews = new ArrayList<>();
        for (ModuleCapability capability : ModuleCapability.resolve(effective, settings.documents().keySet())) {
            moduleViews.add(new ModuleCapabilityView(capability.module(), capability.installed(),
                    capability.enableKey(), capability.enabled(), capability.live()));
        }
        // The optional modules' feature flags are NOT here. Each feature module ships a CapabilityContributor that
        // reports its own flag (walk, gc, search, dependents, scan, provenance, audit) straight onto the one
        // /api/capabilities document this view is itself merged into, so those flags are top-level entries of that
        // body rather than fields of this record. There is one contributor SPI and one merge, and a flag is written
        // by the module that owns it - no second fan-out here lifting the same flags into a fixed view, which is
        // what let the two surfaces disagree after a live toggle. What stays below is what no ServiceLoader
        // fan-out can see: this deployment's Spring-composed beans and tenant kernel.
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

    /** One discovered module's state, enumerated from the settings contributors and provider registries rather than a
     *  maintained table: its JPMS module name, whether it is {@code installed} (on this deployment's module path), the
     *  key of its enablement gate ({@code null} for an always-on module), whether that gate resolves to {@code enabled},
     *  and whether toggling it applies {@code live} (on the next scheduled re-read) or only on the next restart. A
     *  module named only by a leftover stored settings document renders {@code installed == false}. */
    public record ModuleCapabilityView(String module, boolean installed, String enableKey, boolean enabled,
                                       boolean live) {
    }

    public record ImportSourceCapabilityView(String name, String label, boolean requiresFormat) {
    }

    /** One report column contributed by an installed {@code AdvisorySignal}, so a client renders whatever this
     *  deployment's modules contribute. Shares the shape of the vulnerability report's column in {@code compliance/web}. */
    public record SignalColumnView(String name, String label) {
    }

    /** The deployment postures this controller's own Spring context knows and no contributor can see. The
     *  module-contributed flags ({@code scan}, {@code provenance}, {@code audit}, {@code dependents},
     *  {@code search}, {@code walk}, {@code gc}) are deliberately absent: their owning modules contribute them as
     *  top-level entries of the same {@code /api/capabilities} document this view is merged into, so a client reads
     *  each flag from the module that owns it rather than from a copy this controller re-resolved through a second
     *  chain. */
    public record FeaturesView(boolean advisories, boolean advisoriesEnabled, boolean staging, boolean retention,
                               boolean provenanceEnabled, boolean upstream, boolean tokenExchange,
                               boolean rateLimit, boolean readOnly) {
    }
}
