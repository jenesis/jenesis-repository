package build.jenesis.repository.server.kernel;

import build.jenesis.repository.server.RepositoryProperties;
import module java.base;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.Vex;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.settings.SettingsScopes;

/**
 * The runtime-tunable slice of the configuration, held as a live snapshot rebuilt from {@link Settings} layered over
 * the {@link RepositoryProperties} defaults. These are the values that take effect without a restart: the compliance
 * gate (the CVSS threshold, malware action and deny-list, plus every discovered {@link GatePolicy} dimension - the
 * license and known-exploited modules read their own keys through the same effective lookup), the proxy
 * immaturity hold, whether the deployment proxies and to which upstreams, the default tenant, and the deployment
 * retention default. {@link Repositories} reads them per request, so an operator's change through the API, console
 * or CLI is applied on the next request rather than the next deploy.
 *
 * <p>Rebuilding from cheap in-memory {@link Settings} reads keeps the per-request cost to building a few small policy
 * objects. The snapshot is refreshed when a setting is written (so the writing node applies it at once) and on a
 * schedule (so another node's write is applied within the interval), which converges a write-anywhere deployment.
 * The advisory source and the worker/audit lifecycle are not here: they own a client or a thread and so are seeded
 * once at startup from the same settings and only change on a restart.
 */
public final class LiveConfig {

    private final Settings settings;
    private final RepositoryProperties defaults;
    private final AdvisorySource advisories;
    private final UnaryOperator<String> fileDefaults;
    private final Function<String, Optional<String>> pinned;

    /** The tenant's ingested VEX statements, overlaid on every gate this resolves so an advisory a statement marks
     *  not-applicable is suppressed on that tenant's uploads and pull-throughs. Empty ({@link Vex#NONE} for every
     *  tenant) until the deployment wires the store-backed source through {@link #vex}, so the gate is unchanged when
     *  the VEX module is absent or disabled. */
    private volatile Function<String, Vex> vexByTenant = _ -> Vex.NONE;

    private volatile ComplianceGate publishGate;
    private volatile ComplianceGate proxyGate;
    private volatile int holdDays;

    private volatile boolean withholdIncompleteScreens;
    private volatile boolean proxy;
    private volatile String defaultTenant;
    private volatile RetentionPolicy retention;
    private volatile boolean strictHoldMapping;

    /** Without a pin probe, no key is pinned - the store always applies. Used where the environment's precedence is
     *  not in play (the unit tests, a fixed embedded start). */
    public LiveConfig(Settings settings, RepositoryProperties defaults, AdvisorySource advisories,
                      UnaryOperator<String> fileDefaults) {
        this(settings, defaults, advisories, fileDefaults, _ -> Optional.empty());
    }

    /** {@code fileDefaults} answers a plugin policy's keys from the deployment's file/env configuration (null when
     *  unset), so a discovered dimension sees the same layering - runtime settings over deployment config - as the
     *  core dials without this class enumerating any plugin key. {@code pinned} answers the {@link PinnedSettings}
     *  origin probe: a key an operator has pinned from a higher-precedence source resolves to that pin, and the stored
     *  value is inert - the store never overrides an explicit operator pin. */
    public LiveConfig(Settings settings, RepositoryProperties defaults, AdvisorySource advisories,
                      UnaryOperator<String> fileDefaults, Function<String, Optional<String>> pinned) {
        this.settings = settings;
        this.defaults = defaults;
        this.advisories = advisories;
        this.fileDefaults = fileDefaults;
        this.pinned = pinned;
        rebuild();
    }

    /**
     * Re-read the live settings into a new snapshot. Every value is parsed into a local first and the volatile fields
     * are assigned only once they all succeed, so a setting that fails to parse (a bad severity, duration or number)
     * leaves the last good configuration in place rather than wedging a half-updated one - and throws, so the writer
     * (the {@code config/web} adapter's settings mutation) can reject and roll it back, and the scheduled re-read
     * ({@link SettingsRefresh}) just logs and keeps serving the last good values.
     */
    public void rebuild() {
        Snapshot snapshot = resolve(settings::getOrDefault);
        publishGate = snapshot.publishGate();
        proxyGate = snapshot.proxyGate();
        holdDays = snapshot.holdDays();
        withholdIncompleteScreens = snapshot.withholdIncompleteScreens();
        proxy = snapshot.proxy();
        defaultTenant = snapshot.defaultTenant();
        retention = snapshot.retention();
        strictHoldMapping = snapshot.strictHoldMapping();
    }

    /**
     * Dry-resolve a candidate set of stored overrides without applying it, throwing if any live-tunable value fails to
     * parse - the guard an import runs before writing any document, so a malformed value is rejected up front rather
     * than wedging the running configuration once persisted. The candidate wins over the file/env defaults exactly as
     * the live store does (an absent key falls back to the default); an operator pin still wins over both, matching
     * {@link #rebuild()}. Nothing is assigned, so the running snapshot is untouched.
     */
    public void validate(Map<String, String> candidateOverrides) {
        resolve((key, fallback) -> {
            String value = candidateOverrides.get(key);
            return value != null ? value : fallback;
        });
    }

    /**
     * Dry-resolve {@code tenant}'s gate with {@code key}={@code value} overlaid on its stored overrides, throwing if
     * the candidate wedges the tenant gate (a kind-valid but plugin-rejected value - a malformed policy expression a
     * {@link GatePolicyProvider} refuses at resolve time). A per-tenant write goes to a gate resolved on demand rather
     * than through {@link #rebuild()}, so without this a bad tenant value persists and then throws on every one of that
     * tenant's publishes/proxy fetches until it is cleared; validating first lets the writer refuse it. Nothing is
     * assigned, so the running configuration is untouched.
     */
    public void validateTenant(String tenant, String key, String value) {
        resolve((candidateKey, fallback) -> {
            if (candidateKey.equals(key)) {
                return value != null ? value : fallback;
            }
            return settings.getOrDefault(tenant, candidateKey, fallback);
        });
    }

    /**
     * Parse every live-tunable value into a fresh snapshot, reading each stored override through {@code store} (the
     * live {@link Settings} for {@link #rebuild()}, a candidate map for {@link #validate}). Every value is parsed into
     * a local first and the snapshot is returned only once they all succeed, so a value that fails to parse (a bad
     * severity, duration or number) throws rather than leaving a half-built configuration - the rollback guard the
     * writer and the scheduled re-read both rely on.
     */
    private Snapshot resolve(BiFunction<String, String, String> store) {
        BiFunction<String, String, String> get = (key, fallback) -> {
            Optional<String> pin = pinned.apply(key);
            return pin.isPresent() ? pin.get() : store.apply(key, fallback);
        };
        Severity threshold = Severity.valueOf(
                get.apply("vulnerability-threshold", defaults.getVulnerabilityThreshold()).toUpperCase(Locale.ROOT));
        Verdict malware = Verdict.valueOf(
                get.apply("malware-action", defaults.getMalwareAction()).toUpperCase(Locale.ROOT));
        Verdict vulnerable = Verdict.valueOf(
                get.apply("vulnerability-action", defaults.getVulnerabilityAction()).toUpperCase(Locale.ROOT));
        Verdict denyAction = Verdict.valueOf(
                get.apply("deny-list-action", defaults.getDenyListAction()).toUpperCase(Locale.ROOT));
        List<String> denied = new ArrayList<>(tokens(get.apply("deny-list", defaults.getDenyList())));
        // The discovered dimensions read their own keys (runtime settings over the deployment's file/env config);
        // a value that fails to parse throws here, so the writer's rollback and the scheduled re-read's
        // keep-last-good semantics cover plugin policies exactly like the core dials.
        UnaryOperator<String> config = key -> store.apply(key, fileDefaults.apply(key));
        ComplianceGate publishGate = gate(threshold, vulnerable, malware, denied, denyAction,
                GatePolicyProvider.resolve(config, GatePolicyProvider.Path.PUBLISH));
        ComplianceGate proxyGate = gate(threshold, vulnerable, malware, denied, denyAction,
                GatePolicyProvider.resolve(config, GatePolicyProvider.Path.PROXY));
        int holdDays = Integer.parseInt(
                get.apply("immaturity-hold-days", Integer.toString(defaults.getImmaturityHoldDays())));
        boolean proxy = Boolean.parseBoolean(get.apply("proxy-enabled", Boolean.toString(defaults.isProxyEnabled())));
        String defaultTenant = get.apply("default-tenant", defaults.getDefaultTenant());
        RetentionPolicy retention = buildRetention(get);
        // CEP-P2 (C1-A2): whether the publish-time hold-mapping round-trip check throws on a break (failing the publish)
        // or only alarms. Off by default - production stays alarm-not-abort so one broken blobs-namespace format cannot
        // DoS publishes - and flipped on in every test config so a broken mapping fails on the first publish in CI. Read
        // through the same store-over-file/env effective lookup the discovered gate dimensions use.
        boolean strictHoldMapping = Boolean.parseBoolean(config.apply("strict-hold-mapping"));
        // D-246: whether a screen that reached ALLOW over a body it could not finish reading WITHHOLDS, or only says
        // so. Off by default, deliberately: the kit's CONTENT_FINDINGS reading is a declared, reviewed fail-open -
        // an inspector stopped by a bound may only UNDER-declare - so withholding every artifact a scanner could not
        // finish would hold the repository closed on ordinary large ones. The blast radius is size-dependent and
        // differs entirely by deployment, which is what a dial is for: an attestation-required deployment turns it
        // on and accepts that every proxied artifact past the full-body tier is held pending review.
        boolean withholdIncompleteScreens = Boolean.parseBoolean(config.apply("withhold-incomplete-screens"));
        return new Snapshot(publishGate, proxyGate, holdDays, proxy, defaultTenant, retention, strictHoldMapping,
                withholdIncompleteScreens);
    }

    /** The resolved live-tunable configuration, parsed together so a bad value throws before any is applied. */
    private record Snapshot(ComplianceGate publishGate, ComplianceGate proxyGate, int holdDays, boolean proxy,
                            String defaultTenant, RetentionPolicy retention, boolean strictHoldMapping,
                            boolean withholdIncompleteScreens) {
    }

    /** Wire the store-backed VEX source: a function from tenant to that tenant's ingested statements, overlaid on the
     *  publish- and proxy-path gates this resolves so a tenant's VEX suppresses a non-applicable advisory on its own
     *  uploads and pull-throughs. Set once at boot by the deployment; unset, every gate reads {@link Vex#NONE} and the
     *  gate is unchanged (the VEX module absent, or the feature disabled). */
    public void vex(Function<String, Vex> vexByTenant) {
        this.vexByTenant = vexByTenant;
    }


    public ComplianceGate publishGate() {
        return publishGate;
    }

    /** The publish-path compliance gate for a tenant: the deployment-wide gate where the tenant has overridden none of
     *  its policy keys (the precomputed snapshot, no per-request cost), otherwise a gate resolved from that tenant's
     *  effective chain (Spring pin &gt; tenant document &gt; global document &gt; packaged default) so a tenant's own
     *  deny list, CVSS threshold, malware verdict or version floor bites on its uploads while a deployment-wide knob
     *  stays uniform. Built on demand from cheap in-memory settings reads, as the deployment-wide gate is. */
    /**
     * A tenant's effective settings lookup - the same one this snapshot built its gate dimensions from: a stored
     * value layered over the deployment's file and environment defaults.
     *
     * <p>It is exposed because the gate is not the only thing built from configuration. An inspector that verifies a
     * publisher's signature needs the keys an operator configured, and reading them anywhere else answers about a
     * different deployment than the one the gate was built for - which is exactly what happened when that lookup went
     * to the boot environment instead: keys were accepted, shown back on the settings screen, and not believed.
     */
    public UnaryOperator<String> settings(String tenant) {
        return key -> settings.getOrDefault(tenant, key, fileDefaults.apply(key));
    }

    public ComplianceGate publishGate(String tenant) {
        Snapshot tenantGate = tenantGate(tenant);
        ComplianceGate base = tenantGate == null ? publishGate : tenantGate.publishGate();
        return base.vex(vexByTenant.apply(tenant));
    }

    public ComplianceGate proxyGate() {
        return proxyGate;
    }

    /** A tenant's gate for one {@link GatePolicyProvider.Path} flavour - {@link #publishGate(String)} or
     *  {@link #proxyGate(String)} chosen by the flavour rather than by the call site. The router takes this so its
     *  re-screen legs can pick per stored artifact ({@code RescreenFlavor}) while its upstream-fetch legs keep
     *  naming {@code PROXY} explicitly. */
    public ComplianceGate gate(String tenant, GatePolicyProvider.Path path) {
        return path == GatePolicyProvider.Path.PUBLISH ? publishGate(tenant) : proxyGate(tenant);
    }

    /** The proxy-path (fetch firewall) compliance gate for a tenant, resolved through the same effective chain as
     *  {@link #publishGate(String)} so a tenant's stricter policy screens its pull-through fetches too, falling back to
     *  the deployment-wide gate where the tenant has overridden nothing. */
    public ComplianceGate proxyGate(String tenant) {
        Snapshot tenantGate = tenantGate(tenant);
        ComplianceGate base = tenantGate == null ? proxyGate : tenantGate.proxyGate();
        return base.vex(vexByTenant.apply(tenant));
    }

    /** A tenant's resolved live-tunable snapshot, or {@code null} when the tenant has no stored overrides (so the
     *  caller uses the precomputed deployment-wide fields). Resolved on demand - a publish/proxy is not the hot read
     *  path, and building a few small policy objects from in-memory settings is the same cost {@link #rebuild()} pays. */
    private Snapshot tenantGate(String tenant) {
        if (tenant == null || tenant.isBlank() || !settings.tenantConfigured(tenant)) {
            return null;
        }
        return resolve((key, fallback) -> settings.getOrDefault(tenant, key, fallback));
    }

    /** Whether a screen that could not read the whole artifact withholds rather than serving with the fact recorded
     *  (D-246). False by default - the reviewed fail-open. */
    public boolean withholdIncompleteScreens() {
        return withholdIncompleteScreens;
    }

    public int holdDays() {
        return holdDays;
    }

    public boolean proxy() {
        return proxy;
    }

    public String defaultTenant() {
        return defaultTenant;
    }

    /** Whether the publish-time hold-mapping round-trip check ({@code ComplianceScreen.onPublished}) throws on a break
     *  rather than only alarming - {@code jenreg.strict-hold-mapping}, off by default so a broken
     *  blobs-namespace format alarms without DoS-ing publishes, on in the test config so it fails the first publish. */
    public boolean strictHoldMapping() {
        return strictHoldMapping;
    }

    public RetentionPolicy retention() {
        return retention;
    }

    /** The {@code allow-redeploy} opt-out key: with it {@code false} (the default), release-version
     *  immutability is ON and the deploy path refuses re-pointing an already-published immutable release
     *  coordinate at different bytes. Declared through {@code ImmutabilitySettingsContributor}. */
    static final String ALLOW_REDEPLOY = "allow-redeploy";

    /**
     * Whether {@code tenant} has opted out of release-version immutability - {@code allow-redeploy=true}, letting a
     * release coordinate be re-pointed at different bytes. Default {@code false} (immutability default-ON, §the 
     * secure default). Tenant-overridable, so a tenant may relax it for its own artifact space (an operator pin above
     * the store still wins, as it does for every live dial). Resolved on demand off cheap in-memory settings, exactly
     * as the per-tenant gate is - a publish is not the hot read path. */
    public boolean allowRedeploy(String tenant) {
        Optional<String> pin = pinned.apply(ALLOW_REDEPLOY);
        String value = pin.isPresent() ? pin.get() : settings.getOrDefault(tenant, ALLOW_REDEPLOY, "false");
        return Boolean.parseBoolean(value);
    }

    private RetentionPolicy buildRetention(BiFunction<String, String, String> get) {
        // The one parser in the cleanup contracts builds the policy; this only layers the live settings over the
        // deployment defaults per key.
        return RetentionPolicy.fromConfig(key -> switch (key) {
            case "keep-last" -> get.apply(key, Integer.toString(defaults.getKeepLast()));
            case "max-age" -> get.apply(key, defaults.getMaxAge());
            case "prerelease-expiry" -> get.apply(key, defaults.getPrereleaseExpiry());
            case "not-downloaded-for" -> get.apply(key, defaults.getNotDownloadedFor());
            default -> null;
        });
    }

    /** The deployment's {@code proxy-allow-internal} dial - one key, read the same way every other core dial is (the
     *  stored setting over the file/env default), and shared with the proxy legs' screen on upstream-advertised URLs
     *  so the two cannot disagree about the same deployment. */
    public boolean proxyAllowInternal() {
        return Boolean.parseBoolean(effective(ProxyLeg.ALLOW_INTERNAL, "false"));
    }

    /** Whether batch archive ingestion is switched on: the runtime-stored {@code batch-upload} over the deployment
     *  default, read live so an operator's toggle applies on the next request rather than the next restart. */
    public boolean batchUpload() {
        return Boolean.parseBoolean(effective("batch-upload", Boolean.toString(defaults.isBatchUpload())));
    }

    /** The live ceiling on how many members one exploded archive may publish: the runtime-stored
     *  {@code batch-upload-max-entries} over the deployment default. */
    public int batchUploadMaxEntries() {
        return Integer.parseInt(effective("batch-upload-max-entries", Integer.toString(defaults.getBatchUploadMaxEntries())));
    }

    /** Whether demo mode is on: the runtime-stored {@code demo} over the deployment default. Read once at boot by the
     *  seeding trigger (the seed runs post-boot against an empty space), so switching it live takes effect on the
     *  next restart. */
    public boolean demo() {
        return Boolean.parseBoolean(effective("demo", Boolean.toString(defaults.isDemo())));
    }

    /** A proxy upstream override for a format: the runtime-stored one ({@code format-upstream.<format>}, the live
     *  override) over the boot-time default from the per-format map ({@code jenreg.proxy.<format>},
     *  read here through the file/env lookup), or {@code null} when neither is set - the caller then falls back to the
     *  format's own {@code ProxyFormat.defaultUpstream()}, so no table of format names to default upstream URLs lives
     *  here. One concept, two sources: the property is the boot default, the setting is the live override. */
    public String formatUpstream(String format) {
        return effective(SettingsScopes.upstreamKey(format), fileDefaults.apply("proxy." + format));
    }

    /** The effective value of a key: an operator's pin from a higher-precedence source wins outright (the store is
     *  inert for a pinned key); otherwise the stored override, otherwise the file/env fallback. Public because the
     *  router's live definitions read their {@code repositories.<name>} keys through exactly this precedence, and a
     *  second reading of it in the gateway would be a second place for the pin rule to drift. */
    public String effective(String key, String fallback) {
        Optional<String> pin = pinned.apply(key);
        return pin.isPresent() ? pin.get() : settings.getOrDefault(key, fallback);
    }

    private ComplianceGate gate(Severity threshold, Verdict vulnerable, Verdict malware, List<String> denied,
                                Verdict denyAction, List<GatePolicy> policies) {
        return new ComplianceGate(new VulnerabilityPolicy(threshold).action(vulnerable), advisories)
                .malicious(new MaliciousPackagePolicy().action(malware))
                .denyList(new DenyListPolicy(denied).action(denyAction))
                .policies(policies);
    }

    private static SequencedSet<String> tokens(String csv) {
        SequencedSet<String> tokens = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }
}
