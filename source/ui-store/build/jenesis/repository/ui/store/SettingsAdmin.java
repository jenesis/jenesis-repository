package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.ui.ScopedPosture;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.observation.SpiCatalog;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.settings.ModuleCapability;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.TenantPosture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.upstream.UpstreamCredential;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;

/**
 * The console's gateway to the deployment-wide runtime settings, the same {@code config/settings/<module>.json}
 * documents the repository server reads and the {@code /api/settings} endpoint and the CLI edit - so all three
 * administration surfaces drive one store of truth ({@link StoredConfig}). Unlike {@link RepositoryAdmin}, these are
 * not tenant-scoped: they belong to the whole deployment, so the screen is super-admin only and the documents live at
 * the store root rather than under a tenant. A change here is written straight to the shared store; the running
 * repository nodes apply a live setting on their next scheduled re-read and a restart-only setting on their next boot
 * (the screen says which is which).
 *
 * <p>The catalogue is the discovered {@code SettingsContributor} list, not a hand-inlined copy: the neutral core
 * dogfoods the SPI ({@code CoreSettingsContributor} in the settings module) exactly as its plugin modules do, so the
 * console and the {@code /api/settings} adapter share one source of truth for the core dials rather than each carrying
 * a byte-for-byte duplicate. The screen therefore lists exactly the settings of the modules installed on this
 * deployment, the core included.
 */
public class SettingsAdmin {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+");

    /** How long a computed orphaned-data snapshot is reused before the multi-tenant store walk is repeated. The
     *  diagnostic's walk scans every tenant's declared key-spaces for each not-installed module (potentially the
     *  whole store), so repeating it on every modules-screen render would make the reader pay a deployment-wide scan
     *  (§7 read-first). The diagnostic tolerates a short staleness by its own contract - it only counts,
     *  never acts, and reclaiming is an explicit operator purge - so the snapshot is memoised for this window and the
     *  walk runs at most once per window however often the screen is rendered. */
    private static final Duration ORPHAN_TTL = Duration.ofSeconds(60);

    /**
     * How long a collected {@link CollectedPosture} is reused before the effective-settings read behind it is
     * repeated. The header badge and the Security-posture screen now read <em>the same collected report</em>
     * rather than two differently-derived numbers, which means the collection rides <em>every</em> console view -
     * and a per-view read of the deployment's and the tenant's settings documents is exactly the cost
     * &sect;7 keeps off a reader. So it is collected at most once per tenant per window however often a page renders,
     * and the window is short (versus {@link #ORPHAN_TTL}'s minute) because this one is not a store walk but a
     * handful of small documents, and because posture is what an operator watches while changing settings.
     *
     * <p>A change made through <em>this</em> console drops the memo outright, so an operator never waits out the
     * window for their own edit; the window only covers a change made elsewhere (the API, the CLI, another node),
     * and {@link CollectedPosture#collectedAt()} is rendered so that staleness is stated rather than hidden.
     */
    private static final Duration POSTURE_TTL = Duration.ofSeconds(10);

    /** How many tenants' collected posture reports are memoised at once. A console session selects real tenants, so
     *  this is generous; it exists because the map is keyed by an outside-supplied name and a cache must be bounded.
     *  Past the cap it is cleared outright - it is only a cache, and a cleared entry merely re-collects. */
    private static final int POSTURE_TENANTS = 64;

    private final ArtifactStore root;
    private final UpstreamCredentialSource upstreamCredentials;
    private final Function<String, Optional<Pin>> pins;
    private final Supplier<List<String>> tenants;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;

    /** The memoised orphaned-data snapshot and its expiry, guarding the deployment-wide diagnostic walk against
     *  per-render recomputation. Read and refreshed under {@link #orphanLock}; the snapshot is deployment-global
     *  (the scan spans every tenant and this super-admin screen shows the same view to all), so caching it on this
     *  shared instance is correct rather than tenant-leaking. */
    private final Object orphanLock = new Object();
    private Map<String, StorageNamespaces.Report> orphanSnapshot;
    private Instant orphanExpiry = Instant.MIN;
    /** When the current memoised snapshot was actually walked (not the cache's expiry), so the modules screen shows how
     *  fresh its orphaned-data diagnostic is (Principle 10: staleness is visible). {@code null} until the first walk. */
    private Instant orphanScannedAt;

    /** The memoised collected posture per tenant (the empty string being the tenant-less, deployment-wide view), so
     *  the header badge and the Security-posture screen are served one collection rather than each performing their
     *  own - which is what makes them unable to disagree. Dropped whole on any settings write through this
     *  instance. */
    private final ConcurrentMap<String, MemoisedPosture> postureSnapshots = new ConcurrentHashMap<>();

    /** One memoised collection and the instant it stops being reused. */
    private record MemoisedPosture(CollectedPosture collected, Instant until) {
    }

    /** Without a pin probe no key is pinned - the store always applies. Used by fixtures and any surface that does
     *  not layer stored settings under operator pins. */
    public SettingsAdmin(ArtifactStore repositoryStore) {
        this(repositoryStore, _ -> Optional.empty());
    }

    /** Without a tenant directory the orphaned-data diagnostic has no scopes to scan and stays silent. */
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<Pin>> pins) {
        this(repositoryStore, pins, List::of);
    }

    /** A fixture constructor that records no audit events (the no-op trail, no bound tenant, a neutral actor). The
     *  console binds the seven-argument constructor so a privileged settings mutation is audited. */
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<Pin>> pins,
                         Supplier<List<String>> tenants) {
        this(repositoryStore, pins, tenants, AuditTrail.none(), () -> null, () -> "console");
    }

    /** {@code pins} answers the runtime-settings precedence probe (the server's {@code PinnedSettings}, adapted by the
     *  console's Spring layer from its environment): a key an operator has pinned from a source above the store - an
     *  environment variable, a {@code -D} system property, the command line or an external config file - is reported
     *  pinned, so the screen greys its knob, names what pins it and shows the pin's value as effective, and a save
     *  attempt is refused. The store value of a pinned key is inert; the store never overrides an explicit pin.
     *  {@code tenants} is the deployment's tenant directory, the scopes the orphaned-data diagnostic scans. A
     *  privileged settings mutation records an audit event through {@code audit}, attributed to the {@code actor}
     *  under the {@code current} tenant, using the same {@code action}/{@code target} the {@code /api} ConfigController
     *  emits, so the console and the API audit settings changes identically. */
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<Pin>> pins,
                         Supplier<List<String>> tenants, AuditTrail audit, CurrentTenant current, ConsoleActor actor) {
        this(repositoryStore, pins, tenants, audit, current, actor, _ -> null);
    }

    /** The console-bound constructor, additionally given the {@code credentialConfig} the upstream-credential source
     *  reads its deploy-time bootstrap keys from - notably {@code secrets-key} ({@code JENREG_SECRETS_KEY}),
     *  the master key that envelope-encrypts a stored credential at rest. The console reads
     *  it from its own environment exactly as the {@code /api} path does, so a credential set through the console is
     *  encrypted under the same key and refused the same way when none is configured (§9). A fixture that passes no
     *  config resolves an unconfigured cipher, so any credential write it makes is refused - as at rest it must be. */
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<Pin>> pins,
                         Supplier<List<String>> tenants, AuditTrail audit, CurrentTenant current, ConsoleActor actor,
                         UnaryOperator<String> credentialConfig) {
        this.root = repositoryStore;
        // The console manages upstream credentials through the same discovered source as the server; NONE when
        // the module is absent, and the card is hidden through the capability flag.
        this.upstreamCredentials = UpstreamCredentialSourceProvider.resolve(repositoryStore, credentialConfig);
        this.pins = pins;
        this.tenants = tenants;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
    }

    /** Record a privileged settings mutation on the shared audit trail under the console's selected tenant, attributed
     *  to the acting member - the same seam the repository services and {@code CredentialService} use. Best-effort:
     *  a failed audit write never fails the settings mutation it records. */
    private void audit(String action, String target) {
        audit.record(current.name(), actor.name(), action, target);
    }

    /** The editable settings grouped for the form: each carries its documentation, its value kind and choices, its
     *  effective value, its default, whether an override is in force, whether a change applies live or only on the
     *  repository's next restart, and whether it is a high-impact policy knob a change should be confirmed on. The
     *  metadata is exactly what the catalogue already holds - the config page surfaces it, it is not re-authored. */
    public List<Group> groups() throws IOException {
        Properties stored = read();
        Map<String, String> attribution = SettingsContributor.attribution();
        Map<String, List<SettingView>> grouped = new LinkedHashMap<>();
        for (Setting setting : catalogue()) {
            String override = stored.getProperty(setting.key());
            Optional<Pin> pin = pins.apply(setting.key());
            // A pinned key resolves to the operator's pin, not the store: the effective value shown is the pin's,
            // and the store override (if any) is inert.
            String effective = pin.map(Pin::value).orElse(override != null ? override : setting.defaultValue());
            grouped.computeIfAbsent(setting.group(), _ -> new ArrayList<>())
                    .add(view(setting, effective, setting.defaultValue(), override != null, pin,
                            moduleOf(attribution, setting.key())));
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((name, settings) -> groups.add(new Group(name, settings)));
        return groups;
    }

    /** The rows for {@code keys}, in that order, for the keys the catalogue carries - what the first-run setup guide
     *  renders per step, exactly as {@link #groups()} renders the settings screen; a key no installed contributor
     *  declares is left out rather than invented. */
    public List<SettingView> views(List<String> keys) throws IOException {
        Properties stored = read();
        Map<String, String> attribution = SettingsContributor.attribution();
        Map<String, Setting> declared = new HashMap<>();
        for (Setting setting : catalogue()) {
            declared.put(setting.key(), setting);
        }
        List<SettingView> views = new ArrayList<>();
        for (String key : keys) {
            Setting setting = declared.get(key);
            if (setting == null) {
                continue;
            }
            String override = stored.getProperty(key);
            Optional<Pin> pin = pins.apply(key);
            String effective = pin.map(Pin::value).orElse(override != null ? override : setting.defaultValue());
            views.add(view(setting, effective, setting.defaultValue(), override != null, pin,
                    moduleOf(attribution, key)));
        }
        return views;
    }

    /** One key's effective value as this console sees it - the operator's pin, else the stored override, else
     *  {@code fallback} - for a console decision that reads a dial rather than rendering it. */
    public String effective(String key, String fallback) throws IOException {
        Optional<Pin> pin = pins.apply(key);
        if (pin.isPresent()) {
            return pin.get().value();
        }
        return read().getProperty(key, fallback);
    }

    /** Build one setting's view: its effective value against {@code baseline} (the product default globally, the global
     *  effective value in a tenant view), whether an override is in force, its live/restart and pin state, and the JPMS
     *  module that contributes it (so both the settings screen and the modules screen attribute it). */
    private SettingView view(Setting setting, String effective, String baseline, boolean overridden,
                             Optional<Pin> pin, String module) {
        return new SettingView(setting.key(), setting.group(), setting.label(), setting.description(),
                setting.kind().name(), setting.choices(), effective, baseline, overridden, setting.live(),
                highImpact(setting), pin.isPresent(), pin.map(Pin::source).orElse(""), module);
    }

    /** The JPMS module a key is attributed to - the contributor that declares it, or {@link SettingsDocuments#NEUTRAL}
     *  for a neutral core dial or a map entry. */
    private static String moduleOf(Map<String, String> attribution, String key) {
        return attribution.getOrDefault(key, SettingsDocuments.NEUTRAL);
    }

    /** The installed/enabled state of every discovered module, each with its contributed settings beneath it and its
     *  enable/disable toggle where it declares an enablement gate - the modules console. Enumerated from the settings
     *  contributors and the stored documents, not a maintained table: a module named only by a leftover stored document
     *  (this image was not built with it) renders not-installed. The effective value that decides a gate's enabled state
     *  follows the same chain the screen shows - an operator's pin over the stored value over the product default.
     *  A not-installed module whose persisted storage manifest still holds data carries the orphaned-data counts - a
     *  diagnostic only; reclaiming it is the operator's explicit purge command, never a screen side effect. */
    public List<ModuleView> modules() throws IOException {
        Properties stored = read();
        Map<String, String> attribution = SettingsContributor.attribution();
        UnaryOperator<String> effective = key -> {
            Optional<Pin> pin = pins.apply(key);
            return pin.isPresent() ? pin.get().value() : stored.getProperty(key);
        };
        Set<String> storedModules = new TreeSet<>(StoredConfig.documents(root).keySet());
        Map<String, StorageNamespaces.Report> orphans = orphanedData();
        List<ModuleView> views = new ArrayList<>();
        for (ModuleCapability capability : ModuleCapability.resolve(effective, storedModules)) {
            List<SettingView> settings = new ArrayList<>();
            for (Setting setting : capability.settings()) {
                String override = stored.getProperty(setting.key());
                Optional<Pin> pin = pins.apply(setting.key());
                String value = pin.map(Pin::value).orElse(override != null ? override : setting.defaultValue());
                settings.add(view(setting, value, setting.defaultValue(), override != null, pin,
                        moduleOf(attribution, setting.key())));
            }
            StorageNamespaces.Report orphan = orphans.remove(capability.module());
            views.add(new ModuleView(capability.module(), capability.installed(), capability.enabled(),
                    capability.gated(), capability.enableKey(), capability.live(), capability.toggleable(), settings,
                    orphan == null ? 0 : orphan.objects(), orphan == null ? 0 : orphan.bytes()));
        }
        // A removed module may be named only by its persisted storage manifest (no stored settings document, no
        // contributor) - it still deserves a row, so the operator sees its orphaned data at all.
        orphans.forEach((module, orphan) -> views.add(new ModuleView(module, false, false, false, null, false,
                false, List.of(), orphan.objects(), orphan.bytes())));
        views.sort(Comparator.comparing(ModuleView::module));
        return views;
    }

    /** The plug-in surface grouped by SPI - every discovered contract this deployment carries and the installed
     *  implementations that provide it, each with its declaring module's enabled state and contributed settings. The
     *  per-SPI view over the same {@link ModuleCapability} model {@link #modules()} lists per module, decided by the
     *  same effective-value chain the screens show (an operator's pin over the stored value over the product default),
     *  so the SPI catalogue and the modules screen agree on what is on. Super-admin, alongside the modules screen. */
    public List<SpiCatalog> catalog() throws IOException {
        Properties stored = read();
        UnaryOperator<String> effective = key -> {
            Optional<Pin> pin = pins.apply(key);
            return pin.isPresent() ? pin.get().value() : stored.getProperty(key);
        };
        Set<String> storedModules = new TreeSet<>(StoredConfig.documents(root).keySet());
        return ModuleCapability.catalog(effective, storedModules);
    }


    /** The security-posture screen's model for a named tenant: every potentially-unsafe configuration a
     *  {@code ServiceLoader}-discovered {@code SafetyAdvisor} raises against the effective configuration, each naming
     *  <em>why</em> it is unsafe, the exact {@code jenreg.*} key/value that fixes it and a docs link, severity-sorted
     *  (critical first) and split by {@link ScopedPosture} into what this tenant's view may see.
     *
     *  <p>The effective configuration is the one that tenant's deployment would actually run with - the same chain
     *  {@link #groups(String)} shows and the repository server's live configuration resolves: an operator
     *  <em>pin</em> over that tenant's document (for a {@link SettingsScopes#tenantOverridable} key only, so a
     *  deployment-wide dial can never pick up a tenant's value) over the deployment document over the
     *  {@code deployment} lookup the caller hands in ({@code environment::getProperty} from the console's Spring
     *  layer). A non-{@code jenreg.} key ({@code spring.profiles.active}) is read straight off the deployment.
     *
     *  <p>Exactly one tenant's document is consulted, so a report collected here can carry a tenant-scoped row only
     *  about {@code tenant} - and {@link ScopedPosture} then renders only that tenant's rows, so the two scoping
     *  layers are independent. The tenant reaches the advisors through the reserved {@link TenantPosture#scoped}
     *  context key rather than the environment, so an ambient value cannot re-attribute a report.
     *
     *  <p>Read-only - observing posture never mutates it - and it names the risk, never a secret value. A clean
     *  deployment reports nothing, the healthy state. Super-admin, alongside the modules and SPI catalogue screens.
     *  A {@code null} or blank {@code tenant} - a session that has selected none - degrades to the deployment-wide
     *  half alone rather than guessing one; the console is always a tenant view, implicitly so on a single-tenant
     *  deployment where the session selects the one accessible tenant.
     *
     *  <p><b>One collection, two surfaces.</b> This is also what the console header's posture badge counts.
     *  The badge used to collect its own report over the raw Spring environment while this screen read the stored
     *  chain, so an advisory raised by a <em>stored</em> dial was listed here and counted as zero there - and 
     *  widened the gap by giving the screen tenant rows the badge never had. Both now read the value this method
     *  returns, for the same session-selected tenant, so they cannot disagree about the chain, about the tenant, or
     *  about an advisory: the badge counts exactly the rows the screen it links to renders. The result is memoised
     *  for {@link #POSTURE_TTL} because the badge rides every view (&sect;7); the collection instant travels with it
     *  so the screen can say how fresh it is, and a settings write through this console drops the memo at once.
     *
     *  <p>{@code deployment} is the caller's own environment lookup and is <em>not</em> part of the memo key: every
     *  call site is the console's single Spring {@code Environment}, so one collection answers them all. A surface
     *  that layered a different deployment lookup under the same store would need its own instance. */
    public CollectedPosture posture(String tenant, UnaryOperator<String> deployment) throws IOException {
        String selected = tenant == null ? "" : tenant.strip();
        Instant now = Instant.now();
        MemoisedPosture memo = postureSnapshots.get(selected);
        if (memo != null && now.isBefore(memo.until())) {
            return memo.collected();
        }
        CollectedPosture collected = new CollectedPosture(collectPosture(selected, deployment), now);
        if (postureSnapshots.size() >= POSTURE_TENANTS) {
            postureSnapshots.clear();
        }
        postureSnapshots.put(selected, new MemoisedPosture(collected, now.plus(POSTURE_TTL)));
        return collected;
    }

    /** Drop every memoised posture collection, so the next read of the badge or the screen re-collects. Called from
     *  each settings write this console performs: an operator who has just changed a dial must see the advisory it
     *  raises (or clears) immediately, never at the end of a window. A change made on another surface or node is
     *  picked up when the window lapses instead. */
    private void invalidatePosture() {
        postureSnapshots.clear();
    }

    /** The uncached collection: one tenant's effective chain, evaluated by every discovered advisor and split into
     *  what that tenant's view may see. */
    private ScopedPosture collectPosture(String selected, UnaryOperator<String> deployment) throws IOException {
        String prefix = "jenreg.";
        Properties stored = read();
        Properties overrides = selected.isEmpty() ? new Properties() : StoredConfig.load(root, selected);
        Configuration base = Configuration.of(fullKey -> {
            // The advisor asks by full key (jenreg.auth); the store is keyed by the bare key, so strip the
            // prefix and walk the effective chain, falling back to the deployment lookup - and read a
            // non-jenreg. key (spring.profiles.active) straight off the deployment.
            if (fullKey.startsWith(prefix)) {
                String key = fullKey.substring(prefix.length());
                Optional<Pin> pin = pins.apply(key);
                if (pin.isPresent()) {
                    return pin.get().value();
                }
                if (SettingsScopes.tenantOverridable(key)) {
                    String override = overrides.getProperty(key);
                    if (override != null) {
                        return override;
                    }
                }
                String storedValue = stored.getProperty(key);
                if (storedValue != null) {
                    return storedValue;
                }
            }
            return deployment.apply(fullKey);
        });
        return ScopedPosture.of(PostureReport.discover(TenantPosture.scoped(selected, base)), selected);
    }

    /** The orphaned-data reports keyed by module: a persisted storage-manifest entry whose declaring module is not
     *  installed yet whose key-spaces still hold data. Empty when this surface has no tenant directory to scan. A
     *  fresh mutable copy of the memoised snapshot each call, so {@link #modules()} may consume it (it removes each
     *  matched module and folds the rest into rows) without disturbing the cache. */
    private Map<String, StorageNamespaces.Report> orphanedData() throws IOException {
        return new LinkedHashMap<>(orphanSnapshot());
    }

    /** The current orphaned-data snapshot, recomputing the deployment-wide walk only when the memoised one has
     *  expired (or was never taken). The walk runs outside {@link #orphanLock} so a slow multi-tenant scan never
     *  serialises concurrent modules-screen renders; a race past the expiry simply recomputes an idempotent result. */
    private Map<String, StorageNamespaces.Report> orphanSnapshot() throws IOException {
        synchronized (orphanLock) {
            if (orphanSnapshot != null && Instant.now().isBefore(orphanExpiry)) {
                return orphanSnapshot;
            }
        }
        Map<String, StorageNamespaces.Report> computed = scanOrphanedData();
        synchronized (orphanLock) {
            Instant now = Instant.now();
            orphanSnapshot = computed;
            orphanScannedAt = now;                              // the walk's as-of, surfaced as the diagnostic's staleness
            orphanExpiry = now.plus(ORPHAN_TTL);
            return orphanSnapshot;
        }
    }

    /** The instant the orphaned-data diagnostic snapshot the modules screen renders was last walked - Principle 10's
     *  staleness line for that deployment-wide derived view, so an operator knows a just-purged module may linger in
     *  the counts for up to the {@code ORPHAN_TTL} window rather than reading a stale count as current. {@code null}
     *  before the first walk. Reflects the memoised snapshot's actual walk time, not the cache expiry, and is refreshed
     *  in lock-step with {@link #orphanSnapshot()} - so a caller reads it right after {@link #modules()}. */
    public Instant orphanScannedAt() {
        synchronized (orphanLock) {
            return orphanScannedAt;
        }
    }

    /**
     * Purge the named module's declared key-spaces across every tenant - the console's explicit reclamation of the
     * orphaned data the modules screen names, the same primitive {@code POST /api/admin/purge} drives and audited
     * the same way. The memoised orphan snapshot is dropped so the next render reads the post-purge store rather
     * than the stale counts. Empty when no manifest entry names the module.
     */
    public Optional<StorageNamespaces.Report> purgeOrphanedData(String module) throws IOException {
        Optional<StorageNamespaces.Report> report = new StorageNamespaces(root).purge(module, tenants.get());
        if (report.isPresent()) {
            audit("storage.purge", module + " (" + report.get().objects() + " objects, "
                    + report.get().bytes() + " bytes)");
            synchronized (orphanLock) {
                orphanSnapshot = null;
                orphanExpiry = Instant.MIN;
            }
        }
        return report;
    }

    /** The uncached diagnostic walk: for every not-installed manifest entry, the count of what its key-spaces still
     *  hold across every tenant scope. Empty when this surface has no tenant directory to scan. */
    private Map<String, StorageNamespaces.Report> scanOrphanedData() throws IOException {
        List<String> scopes = tenants.get();
        if (scopes.isEmpty()) {
            return Map.of();
        }
        Map<String, StorageNamespaces.Report> orphans = new LinkedHashMap<>();
        for (StorageNamespaces.Report report : new StorageNamespaces(root).orphans(scopes)) {
            orphans.put(report.module(), report);
        }
        return orphans;
    }

    /** Whether a setting gates how artifacts are admitted, so the console confirms before applying a change. That is
     *  the compliance area's verdict knobs (a REJECT | QUARANTINE | ALLOW / severity {@code CHOICE}) and its deny
     *  lists - a change here can start rejecting or admitting packages, unlike an endpoint URL or a feed toggle. */
    private static boolean highImpact(Setting setting) {
        return "Compliance".equals(setting.group())
                && (setting.kind() == Setting.Kind.CHOICE
                        || setting.key().toLowerCase(Locale.ROOT).contains("deny"));
    }

    /** Set ({@code value} non-blank) or clear ({@code null}/blank) one override; an unknown key is refused so only
     *  catalogued settings change. Read-modify-write so a concurrent change to another key is not lost. */
    public void save(String key, String value) throws IOException {
        Setting setting = catalogue().stream().filter(candidate -> candidate.key().equals(key))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown setting '" + key + "'."));
        Optional<Pin> pin = pins.apply(key);
        if (pin.isPresent()) {
            throw new IllegalArgumentException("Setting '" + key + "' is pinned by " + pin.get().source()
                    + " and cannot be changed here; the stored value would be inert.");
        }
        if (!value.isBlank() && !setting.parses(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a valid value for setting '" + key + "'.");
        }
        put(key, value);
        // setting.set / setting.clear, the same split the /api ConfigController records (a PUT sets, a DELETE clears);
        // here one save does both, so a blank value is the clear.
        audit(value.isBlank() ? "setting.clear" : "setting.set", key);
    }

    /** The stored settings as one JSON bundle for the console's download - the same layout the {@code /api/settings/export}
     *  endpoint and the CLI emit. The deployment-wide (global) documents keyed by module plus every tenant's slice keyed
     *  {@code tenant:<tenant>:<module>}; with no per-tenant config it is exactly the global bundle. Credential-free by
     *  construction: every SECRET-kind key is excluded, so a stored secret (the keyless identity token) never travels
     *  in a downloaded backup - not by the false "secrets are environment-only" assumption. */
    public byte[] exportBundle() throws IOException {
        return SettingsDocuments.serializeBundle(StoredConfig.exportBundle(root));
    }

    /** Restore an uploaded settings bundle (module name to that module's stored overrides, parsed by the console's web
     *  layer with a real JSON reader): every catalogued value is validated - an unparseable one is refused before
     *  anything is written, so a bad bundle never half-applies - then each module document is written as a whole and a
     *  module the bundle omits is cleared (a full restore). Map entries (repository definitions, format upstreams) are
     *  validated with their own parsers, matching the single-key save path. A pinned key's stored value stays inert. */
    public void importBundle(Map<String, ? extends Map<String, String>> bundle) throws IOException {
        boolean allowInternal = allowInternal();
        Map<String, Setting> catalogue = new LinkedHashMap<>();
        for (Setting setting : catalogue()) {
            catalogue.put(setting.key(), setting);
        }
        for (Map<String, String> document : bundle.values()) {
            if (document == null) {
                continue;
            }
            document.forEach((key, value) -> {
                if (value == null || value.isBlank()) {
                    return;
                }
                Setting setting = catalogue.get(key);
                if (setting != null) {
                    if (!setting.parses(value)) {
                        throw new IllegalArgumentException(
                                "'" + value + "' is not a valid value for setting '" + key + "'.");
                    }
                } else if (key.startsWith(SettingsScopes.REPOSITORY_PREFIX)) {
                    refuseUnusableUpstream(key, RepositoryDefinition.parse(value), value, allowInternal);
                } else if (key.startsWith(SettingsScopes.UPSTREAM_PREFIX)) {
                    refuseUnusableUpstream(key, URI.create(value), allowInternal);
                }
            });
        }
        StoredConfig.importBundle(root, bundle);
        invalidatePosture();
        audit("settings.import", SettingsDocuments.ROOT);
    }

    /** A tenant's runtime-settings groups: only the tenant-overridable keys (the gate policy, deny list and forward
     *  targets a tenant may retune), each with its tenant-effective value along the chain <em>pin &gt; tenant document
     *  &gt; global document &gt; default</em>, its global effective value as the baseline default, and whether this
     *  tenant has overridden it - the console's per-tenant settings view, offered to that tenant's admins. */
    public List<Group> groups(String tenant) throws IOException {
        Properties global = read();
        Properties tenantOverrides = StoredConfig.load(root, tenant);
        Map<String, String> attribution = SettingsContributor.attribution();
        Map<String, List<SettingView>> grouped = new LinkedHashMap<>();
        for (Setting setting : catalogue()) {
            if (!SettingsScopes.tenantOverridable(setting.key())) {
                continue;
            }
            String globalEffective = global.getProperty(setting.key(), setting.defaultValue());
            String tenantValue = tenantOverrides.getProperty(setting.key());
            Optional<Pin> pin = pins.apply(setting.key());
            String effective = pin.map(Pin::value).orElse(tenantValue != null ? tenantValue : globalEffective);
            grouped.computeIfAbsent(setting.group(), _ -> new ArrayList<>())
                    .add(view(setting, effective, globalEffective, tenantValue != null, pin,
                            moduleOf(attribution, setting.key())));
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((name, settings) -> groups.add(new Group(name, settings)));
        return groups;
    }

    /** Set or clear one of a tenant's overrides; an unknown or deployment-wide key is refused so a tenant changes only
     *  its own tenant-overridable settings. Layered over the deployment-wide value, leaving the global settings and
     *  other tenants untouched. */
    public void save(String tenant, String key, String value) throws IOException {
        Setting setting = catalogue().stream().filter(candidate -> candidate.key().equals(key))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown setting '" + key + "'."));
        if (!SettingsScopes.tenantOverridable(key)) {
            throw new IllegalArgumentException("Setting '" + key + "' is deployment-wide and cannot be set per tenant.");
        }
        Optional<Pin> pin = pins.apply(key);
        if (pin.isPresent()) {
            throw new IllegalArgumentException("Setting '" + key + "' is pinned by " + pin.get().source()
                    + " and cannot be changed here; the stored value would be inert.");
        }
        if (!value.isBlank() && !setting.parses(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a valid value for setting '" + key + "'.");
        }
        if (!value.isBlank()) {
            validateTenantGate(tenant, key, value);
        }
        StoredConfig.put(root, tenant, key, value);
        invalidatePosture();
        // Per-tenant setting.set / setting.clear, target tenant/key - the shape ConfigController's tenant path emits.
        audit(value.isBlank() ? "setting.clear" : "setting.set", tenant + "/" + key);
    }

    /** Dry-resolve this tenant's gate with the candidate overlaid, so a kind-valid but plugin-rejected value (a
     *  malformed policy-as-code expression, a bad version floor - values a {@link GatePolicyProvider} refuses only at
     *  gate resolve time, which {@code setting.parses} cannot see) is refused here rather than persisted and then
     *  500ing every one of this tenant's publishes until it is cleared. This is the console's equivalent of the
     *  {@code /api} {@code ConfigController}'s tenant {@code PUT}, which runs the same guard through
     *  {@code LiveConfig.validateTenant}: both dry-resolve the discovered {@code GatePolicyProvider} dimensions over
     *  the tenant's effective chain (pin &gt; tenant document &gt; global document), exactly as the vulnerability panel
     *  reuses the discovered advisory sources. The console reuses the compliance SPI directly rather than the
     *  repository-server module (which it does not depend on). A throwaway resolve; nothing is assigned, so the running
     *  configuration is untouched. */
    private void validateTenantGate(String tenant, String key, String value) throws IOException {
        Properties global = read();
        Properties tenantOverrides = StoredConfig.load(root, tenant);
        UnaryOperator<String> config = candidate -> {
            if (candidate.equals(key)) {
                return value;
            }
            Optional<Pin> pin = pins.apply(candidate);
            if (pin.isPresent()) {
                return pin.get().value();
            }
            if (SettingsScopes.tenantOverridable(candidate)) {
                String tenantValue = tenantOverrides.getProperty(candidate);
                if (tenantValue != null) {
                    return tenantValue;
                }
            }
            return global.getProperty(candidate);
        };
        try {
            GatePolicyProvider.resolve(config, GatePolicyProvider.Path.PUBLISH);
            GatePolicyProvider.resolve(config, GatePolicyProvider.Path.PROXY);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a valid value for setting '" + key + "': " + e.getMessage());
        }
    }

    /** Restore one tenant's slice from an uploaded bundle (module name to that tenant's overrides), a full restore
     *  leaving the global settings and other tenants untouched; a global-only key is refused. */
    public void importTenant(String tenant, Map<String, ? extends Map<String, String>> bundle) throws IOException {
        boolean allowInternal = allowInternal();
        Map<String, Setting> catalogue = new LinkedHashMap<>();
        for (Setting setting : catalogue()) {
            catalogue.put(setting.key(), setting);
        }
        for (Map<String, String> document : bundle.values()) {
            if (document == null) {
                continue;
            }
            document.forEach((key, value) -> {
                if (value == null || value.isBlank()) {
                    return;
                }
                Setting setting = catalogue.get(key);
                if (setting != null && !setting.parses(value)) {
                    throw new IllegalArgumentException(
                            "'" + value + "' is not a valid value for setting '" + key + "'.");
                }
                if (setting == null && key.startsWith(SettingsScopes.REPOSITORY_PREFIX)) {
                    refuseUnusableUpstream(key, RepositoryDefinition.parse(value), value, allowInternal);
                } else if (setting == null && key.startsWith(SettingsScopes.UPSTREAM_PREFIX)) {
                    refuseUnusableUpstream(key, URI.create(value), allowInternal);
                }
            });
        }
        StoredConfig.importTenant(root, tenant, bundle);
        invalidatePosture();
        audit("settings.import", tenant + "/" + SettingsDocuments.ROOT);
    }

    /** The repositories defined at runtime (name to routing spec) - the same {@code repositories.<name>} entries the
     *  API and CLI manage; they add to or override the deployment's file-configured repositories. */
    public Map<String, String> repositories() throws IOException {
        return entries(SettingsScopes.REPOSITORY_PREFIX);
    }

    /** Whether a repository is defined at runtime with a hardened upstream ({@code fallback <url> harden}) - an
     *  untrusted-upstream leg that spools and fully screens every fetched body before releasing a byte. The
     *  console reads this to badge such repositories in the list and gate the hardened verdict/refusal/drift panel. Only
     *  the runtime {@code repositories.<name>} definitions are visible to the console (the same set {@link #repositories}
     *  lists); a repository defined only in the server's file/env config, or one with no hardened upstream fallback,
     *  reads as not hardened. A malformed stored specification degrades to not hardened rather than
     *  throwing out of a list render. */
    public boolean hardened(String name) throws IOException {
        return hardenedDefinition(repositories().get(name));
    }

    /** {@link #hardened(String)} over a definition already read ({@link #repositories}), for a listing that reads
     *  the settings once rather than once per repository. */
    public static boolean hardenedDefinition(String specification) {
        if (specification == null || specification.isBlank()) {
            return false;
        }
        try {
            return RepositoryDefinition.parse(specification).harden();
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * The parsed <em>shape</em> of a repository's runtime definition for the console badges: whether it accepts
     * uploads ({@code writable}) and, per fallback, an upstream's copy ({@code store}) and screen strength or an
     * inner-repository reference - rendered from the one {@link RepositoryDefinition} the router routes on, so the
     * badges never drift from what actually serves. Only the runtime {@code repositories.<name>} definitions the console
     * manages are visible (as {@link #hardened} notes); an unconfigured repository is the default shape - writable, with
     * no fallbacks - and a malformed stored specification degrades to that same neutral shape rather than throwing
     * out of a list render (§10 render-what-you-have). {@link RepositoryShape#warnings} carries the valid-but-risky
     * flags (mixed screening strength, an unscreened or plaintext upstream) the parse logs loudly - surfaced by the
     * console as a non-blocking notice, not a refusal (item 4).
     */
    public RepositoryShape shape(String name) throws IOException {
        return shape(name, repositories().get(name));
    }

    /** {@link #shape(String)} over a specification already read ({@link #repositories}), for a listing that reads
     *  the settings once rather than once per repository. */
    public RepositoryShape shape(String name, String specification) {
        if (specification == null || specification.isBlank()) {
            return new RepositoryShape(false, true, List.of(), List.of());
        }
        RepositoryDefinition definition;
        try {
            definition = RepositoryDefinition.parse(specification);
        } catch (RuntimeException malformed) {
            return new RepositoryShape(false, true, List.of(), List.of());
        }
        List<FallbackBadge> fallbacks = new ArrayList<>();
        for (RepositoryDefinition.Fallback fallback : definition.fallbacks()) {
            switch (fallback.source()) {
                case RepositoryDefinition.Source.Upstream upstream ->
                        fallbacks.add(new FallbackBadge(true, upstream.url().toString(), fallback.store(),
                                screeningLabel(fallback.screening()), null));
                case RepositoryDefinition.Source.Repository repository ->
                        fallbacks.add(new FallbackBadge(false, repository.name(), false, "", repository.name()));
                case RepositoryDefinition.Source.DnsDirectory dns ->
                        // The DNS directory leg (a `fallback dns redirect`) - the upstream is resolved
                        // per request by the DNS walk, so the badge names the `dns` source, stores nothing, and is not a
                        // repository-name view.
                        fallbacks.add(new FallbackBadge(false, "dns", false,
                                screeningLabel(fallback.screening()), null));
            }
        }
        return new RepositoryShape(true, definition.writable(), List.copyOf(fallbacks),
                warnings(definition, specification));
    }

    /** The valid-but-risky warnings a parsed definition carries: the mixed-strength flag (a
     *  hardened upstream beside a weaker one, via the {@link RepositoryDefinition#mixedStrength}
     *  classifier), an {@code unscreened} upstream, and a plaintext ({@code http://}) upstream (via the 
     *  {@link RepositoryDefinition#plaintextUpstream} classifier). Each is a non-blocking notice - the same loud
     *  ⚑ the parse logs - never a refusal. */
    private static List<String> warnings(RepositoryDefinition definition, String specification) {
        List<String> warnings = new ArrayList<>();
        if (RepositoryDefinition.mixedStrength(definition.fallbacks())) {
            warnings.add("mixed screening strength: a 'harden' upstream sits beside a weaker (default/unscreened) "
                    + "upstream, so a weaker fallback ordered before a hardened one can serve first-hit before the "
                    + "strong screen runs. Reorder so the strongest screen leads, or 'harden' the weaker "
                    + "fallback too.");
        }
        for (RepositoryDefinition.Fallback fallback : definition.fallbacks()) {
            if (fallback.source() instanceof RepositoryDefinition.Source.Upstream upstream) {
                if (fallback.screening() == RepositoryDefinition.Screening.UNSCREENED) {
                    warnings.add("unscreened upstream '" + upstream.url() + "': its fetched artifacts are served with "
                            + "NO compliance screening. Remove 'unscreened' or use 'harden' to full-body screen.");
                }
                if (RepositoryDefinition.plaintextUpstream(upstream.url())) {
                    warnings.add("plaintext upstream '" + upstream.url() + "': artifacts and any per-host upstream "
                            + "credential sent to it travel in cleartext. Use an https upstream.");
                }
            }
        }
        return List.copyOf(warnings);
    }

    /** The per-fallback screen-strength badge label - the operator-facing name of the {@code Upstream} fallback's
     *  screening policy (item 2). */
    private static String screeningLabel(RepositoryDefinition.Screening screening) {
        return switch (screening) {
            case DEFAULT -> "default";
            case HARDEN -> "harden";
            case UNSCREENED -> "unscreened";
        };
    }

    /** A repository's parsed shape for the console (item 2): whether the definition is configured at runtime at
     *  all ({@code configured}; an unconfigured or malformed one is the neutral default), whether it accepts uploads
     *  ({@code writable}), its ordered per-fallback badges, and any valid-but-risky {@code warnings} the console
     *  surfaces as a non-blocking notice. A {@code writable} repository with fallbacks is the host+proxy hybrid. */
    public record RepositoryShape(boolean configured, boolean writable, List<FallbackBadge> fallbacks,
                                  List<String> warnings) {
        public RepositoryShape {
            fallbacks = List.copyOf(fallbacks);
            warnings = List.copyOf(warnings);
        }

        /** Whether this repository is a pure read-only view (a proxy or group): configured, not writable. */
        public boolean readOnly() {
            return configured && !writable;
        }

        /** Whether this repository both accepts uploads and consults fallbacks - the host+proxy hybrid (a badge
         *  worth calling out). */
        public boolean hybrid() {
            return writable && !fallbacks.isEmpty();
        }
    }

    /** One fallback's console badge (item 2): an {@code upstream} carries its URL {@code source}, its
     *  {@code store} (cached vs pass-through) and its {@code screening} strength ({@code default}/{@code harden}/
     *  {@code unscreened}); a repository-name fallback ({@code upstream=false}) carries its inner {@code repository}
     *  reference and no store/screen policy (the inner repository owns its own). */
    public record FallbackBadge(boolean upstream, String source, boolean store, String screening, String repository) {
    }

    /** Store a repository definition, validated as the boot sweep validates it. */
    public void setRepository(String name, String specification) throws IOException {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid repository name '" + name + "'.");
        }
        // Write-time validation (§9): run the SAME parser the boot sweep uses BEFORE the
        // definition is stored, so a broken definition never reaches the store. A parse failure is refused LOUD and
        // NAMED - the repository name, what is wrong, and the fix - mirroring LiveConfig.sweepDefinitions so the
        // console write-surface refuses exactly what the boot sweep would. A valid-but-risky definition (unscreened,
        // plaintext, mixed-strength) parses (its warning logged inside parse and surfaced by the console banner via
        // #shape) - only an unparseable one is refused here.
        RepositoryDefinition definition;
        try {
            definition = RepositoryDefinition.parse(specification);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Repository '" + name + "' has an invalid definition '" + specification
                    + "': " + invalid.getMessage() + " Fix the definition (writable / fallback <source> "
                    + "[nocache|harden|unscreened]), or remove it - a "
                    + "repository definition that cannot be parsed is refused rather than stored.", invalid);
        }
        // A plaintext upstream is refused rather than stored with a console notice, exactly as the API write
        // surface and the boot sweep refuse it. The console notice stays for a deployment that took the dial.
        String refused = RepositoryDefinition.upstreamRefusal(definition, allowInternal());
        if (refused != null) {
            throw new IllegalArgumentException("Repository '" + name + "' has a refused definition '" + specification
                    + "': " + refused + "." + RepositoryDefinition.upstreamRemedy());
        }
        put(SettingsScopes.repositoryKey(name), specification);
        audit(AuditActions.REPOSITORY_SET, name);
    }

    public void removeRepository(String name) throws IOException {
        put(SettingsScopes.repositoryKey(name), null);
        audit(AuditActions.REPOSITORY_REMOVE, name);
    }

    /** The per-format proxy upstreams set at runtime (format to upstream URL). */
    public Map<String, String> upstreams() throws IOException {
        return entries(SettingsScopes.UPSTREAM_PREFIX);
    }

    /** Every installed format's public registry ({@link ProxyFormat#defaultUpstream}), by format name, held once. */
    private static final Map<String, String> PUBLIC_REGISTRIES = RepositoryFormat.installed().stream()
            .filter(format -> format instanceof ProxyFormat)
            .flatMap(format -> ((ProxyFormat) format).defaultUpstream().stream()
                    .map(upstream -> Map.entry(format.name(), upstream.toString())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, _) -> first, TreeMap::new));

    /**
     * The public registries of the installed formats that have no upstream named yet - what the console offers to
     * name in one click. A format fetches nothing from its public registry until someone does, so this is the whole
     * of the distance between an installed format and one that pulls through.
     */
    public Map<String, String> suggestedUpstreams() throws IOException {
        Map<String, String> named = upstreams();
        Map<String, String> suggested = new TreeMap<>(PUBLIC_REGISTRIES);
        suggested.keySet().removeAll(named.keySet());
        return suggested;
    }

    public void setUpstream(String format, String url) throws IOException {
        if (!NAME.matcher(format).matches()) {
            throw new IllegalArgumentException("Invalid format name '" + format + "'.");
        }
        // The same screen on the other spelling of the same operator-configured target (see setRepository).
        String refused = RepositoryDefinition.upstreamRefusal(URI.create(url), allowInternal());
        if (refused != null) {
            throw new IllegalArgumentException("The '" + format + "' upstream '" + url + "' is refused: " + refused
                    + "." + RepositoryDefinition.upstreamRemedy());
        }
        put(SettingsScopes.upstreamKey(format), url);
        audit(AuditActions.UPSTREAM_SET, format);
    }

    public void removeUpstream(String format) throws IOException {
        put(SettingsScopes.upstreamKey(format), null);
        audit(AuditActions.UPSTREAM_REMOVE, format);
    }

    private Map<String, String> entries(String prefix) throws IOException {
        Properties stored = read();
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : stored.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                entries.put(key.substring(prefix.length()), stored.getProperty(key));
            }
        }
        return entries;
    }

    /** The upstream hosts that carry a proxy credential for a private registry; only the hosts are returned, never
     *  the credential (it is write-only, kept out of the readable settings). */
    public SortedSet<String> upstreamCredentialHosts() throws IOException {
        return upstreamCredentials.hosts();
    }

    public void setUpstreamCredential(String host, String scheme, String username, String password, String token,
                                      String headerName) throws IOException {
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Invalid upstream host '" + host + "'.");
        }
        UpstreamCredential credential = UpstreamCredential.of(scheme, username, password, token, headerName)
                .orElseThrow(() -> new IllegalArgumentException("Provide a username and password (basic), a token "
                        + "(bearer), a header name and value (header), or choose aws for a token this deployment's "
                        + "AWS identity is issued."));
        upstreamCredentials.set(host, credential);
        audit(AuditActions.UPSTREAM_AUTH_SET, host);
    }

    public void removeUpstreamCredential(String host) throws IOException {
        upstreamCredentials.remove(host);
        audit(AuditActions.UPSTREAM_AUTH_REMOVE, host);
    }


    private void put(String key, String value) throws IOException {
        StoredConfig.put(root, key, value);
        invalidatePosture();
    }

    private Properties read() throws IOException {
        return StoredConfig.load(root);
    }

    /** The deployment's {@code proxy-allow-internal} dial as this console can see it: the stored value, else the
     *  shipped default (off). Like the import guard's console leg, this surface has no {@code RepositoryProperties}
     *  env-field to consult, so a deployment that takes the opt-out through the environment alone is still refused
     *  here and admitted by the boot sweep - the console's stored-only view, stated rather than silently assumed. */
    private boolean allowInternal() throws IOException {
        return Boolean.parseBoolean(read().getProperty(RepositoryDefinition.ALLOW_INTERNAL_SETTING, "false"));
    }

    /** Refuse a repository definition whose upstream this deployment must not pull from, naming the key. */
    private static void refuseUnusableUpstream(String key, RepositoryDefinition definition,
                                               String specification, boolean allowInternal) {
        String refused = RepositoryDefinition.upstreamRefusal(definition, allowInternal);
        if (refused != null) {
            throw new IllegalArgumentException("'" + specification + "' is not an importable value for '" + key
                    + "': " + refused + "." + RepositoryDefinition.upstreamRemedy());
        }
    }

    /** Refuse a {@code format-upstream.<format>} value this deployment must not pull from, naming the key. */
    private static void refuseUnusableUpstream(String key, URI upstream, boolean allowInternal) {
        String refused = RepositoryDefinition.upstreamRefusal(upstream, allowInternal);
        if (refused != null) {
            throw new IllegalArgumentException("'" + upstream + "' is not an importable value for '" + key + "': "
                    + refused + "." + RepositoryDefinition.upstreamRemedy());
        }
    }

    /** The editable-settings catalogue: the neutral core dogfoods the same {@code SettingsContributor} SPI its plugin
     *  modules use ({@code CoreSettingsContributor}), so this is just {@link SettingsContributor#all()} - the core is
     *  described once (in the settings module) rather than inlined here and again in the {@code /api/settings} adapter,
     *  and the console lists exactly the settings of the modules installed on this deployment. */
    private List<Setting> catalogue() {
        return SettingsContributor.all();
    }

    /** One area's settings, for a section on the form. */
    public record Group(String name, List<SettingView> settings) {
    }

    /**
     * One collected security-posture view and the instant it was collected - what both the Security-posture screen
     * renders and the header badge counts, handed out as one value so the two can never be reading different
     * collections. {@code collectedAt} is the report's own as-of: the collection is memoised for
     * {@link #POSTURE_TTL} so the badge does not make every console view pay a settings read, and &sect;10
     * asks that a derived view state its freshness rather than pass a memoised number off as a live one.
     */
    public record CollectedPosture(ScopedPosture posture, Instant collectedAt) {

        public CollectedPosture {
            Objects.requireNonNull(posture, "posture");
            Objects.requireNonNull(collectedAt, "collectedAt");
        }
    }

    /** One module's row on the modules console: its JPMS name, whether it is {@code installed} (on the module path) and
     *  {@code enabled} (its enablement gate resolves on), whether it declares a {@code gated} enable flag at all and
     *  that flag's {@code enableKey}, whether flipping it applies {@code live} (on the next scheduled re-read) or only
     *  on the next restart, whether the gate is a plain boolean the screen renders as a {@code toggleable} switch (a
     *  numeric ceiling is edited as a value beneath instead), and the settings it contributes for the list beneath the
     *  row. A not-installed module (named only by a leftover stored document) carries no settings. A not-installed
     *  module whose declared key-spaces still hold data carries the {@code orphanObjects}/{@code orphanBytes} counts -
     *  the diagnostic that informs, while reclaiming stays an explicit operator purge, never a screen action. */
    public record ModuleView(String module, boolean installed, boolean enabled, boolean gated, String enableKey,
                             boolean live, boolean toggleable, List<SettingView> settings,
                             long orphanObjects, long orphanBytes) {

        public ModuleView {
            settings = List.copyOf(settings);
        }

        /** Whether an enabled/disabled toggle here applies only on the next restart, so the row shows the honest
         *  {@code restart} badge - a gated module whose gate is not live (a tracker or the audit lifecycle owns a
         *  client/thread and is seeded once at boot). */
        public boolean restart() {
            return gated && !live;
        }

        /** The value a boolean toggle posts to flip this module's gate: {@code false} when it is on, {@code true} when
         *  it is off. */
        public String toggleValue() {
            return enabled ? "false" : "true";
        }

        /** Whether this (removed) module's declared key-spaces still hold data the diagnostic should surface. */
        public boolean orphaned() {
            return orphanObjects > 0;
        }
    }

    /** A key pinned above the store: the human phrase for what pins it ("environment variable", "system property",
     *  "command line", "configuration file") and the value it is fixed to. The console's Spring layer produces this
     *  from the server's {@code PinnedSettings} probe over its environment, so this Spring-free layer stays decoupled
     *  from it. */
    public record Pin(String source, String value) {
    }

    /** One setting rendered for the config page: its key and human label, its inline documentation, its value kind
     *  and choice list (so the form renders the right control with inline validation), its effective value and its
     *  default (so the page shows effective-vs-default), whether an override is in force, whether a change takes
     *  effect live (otherwise on the repository's next restart), whether it is a high-impact policy knob to confirm
     *  on save, whether it is pinned from above the store (with the phrase naming what pins it) - a pinned knob
     *  renders greyed and inert - and the JPMS module that contributes it ({@link SettingsDocuments#NEUTRAL the neutral
     *  core} for a core dial), so the settings screen attributes each knob to its module and the modules screen groups
     *  by it. The presentation helpers keep the mapping out of the template so a view holds no logic. */
    public record SettingView(String key, String group, String label, String description,
                              String kind, List<String> choices, String value, String defaultValue,
                              boolean overridden, boolean live, boolean highImpact,
                              boolean pinned, String pinnedBy, String module) {

        public SettingView {
            choices = List.copyOf(choices);
        }

        /** A CHOICE renders as a select of its catalogued options; a BOOLEAN as a {@link #toggle() switch}; every
         *  other kind as a typed text input. */
        public boolean dropdown() {
            return "CHOICE".equals(kind);
        }

        /** A BOOLEAN renders as a switch showing its effective value, which saves the opposite value in one click -
         *  a select of {@code true} and {@code false} beside a Save button asked for two actions to state one bit. */
        public boolean toggle() {
            return "BOOLEAN".equals(kind);
        }

        /** Whether a {@link #toggle() switch} is on: the effective value, as the switch shows it. */
        public boolean on() {
            return Boolean.parseBoolean(value.trim());
        }

        /** The value a {@link #toggle() switch} saves when pressed: the opposite of its effective value. */
        public String flipped() {
            return Boolean.toString(!on());
        }

        /** The fixed options a {@link #dropdown()} offers: the catalogued choices, or true/false for a boolean. */
        public List<String> options() {
            return "BOOLEAN".equals(kind) ? List.of("true", "false") : choices;
        }

        /** The HTML input type that gives a text control inline validation for its kind (a number spinner for the
         *  integral kinds, a URL check for a URI, a masked field for a secret), plain text otherwise. */
        public String inputType() {
            return switch (kind) {
                case "INTEGER", "LONG" -> "number";
                case "URI" -> "url";
                case "SECRET" -> "password";
                default -> "text";
            };
        }

        public boolean secret() {
            return "SECRET".equals(kind);
        }

        /** The effective value for display: a set secret is masked (whether stored or pinned), an empty value reads as
         *  {@code (unset)}. */
        public String effectiveDisplay() {
            if (secret() && (overridden || pinned)) {
                return "••••••";
            }
            return value.isBlank() ? "(unset)" : value;
        }

        /** The default for display, an empty default reading as {@code (unset)}. */
        public String defaultDisplay() {
            return defaultValue.isBlank() ? "(unset)" : defaultValue;
        }

        /** The value to prefill the edit control with - the current override so an operator edits it in place, but
         *  <em>never the plaintext of a secret</em>: a masked field is masked only on screen, so emitting the value
         *  into the {@code value=""} attribute would leak it into the page source / DOM. A secret prefills empty (the
         *  operator re-enters it to change it), matching the masked {@link #effectiveDisplay()}. */
        public String editValue() {
            return secret() || !overridden ? "" : value;
        }

        /** The placeholder for the edit control: a secret shows only whether it is set (never its value or default,
         *  which would defeat the masking); every other kind shows its default, or an unset-default hint. */
        public String editPlaceholder() {
            if (secret()) {
                return overridden ? "(set — re-enter to change)" : "(unset)";
            }
            return defaultValue.isBlank() ? "(unset default)" : defaultValue;
        }
    }
}
