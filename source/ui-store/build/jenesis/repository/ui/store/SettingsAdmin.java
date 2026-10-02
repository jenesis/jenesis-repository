package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.definitions.RoutingSettingsContributor;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.ui.ScopedPosture;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.observation.SpiCatalog;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.settings.ModuleCapability;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.Wizard;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.settings.TenantPosture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.upstream.UpstreamCredential;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsEditor;

/**
 * The console's view of the runtime settings - the deployment's, a tenant's, a repository's and a project's - and its
 * way to change them, through the one {@link SettingsEditor} the {@code /api/settings} endpoints use, in process: the
 * same refusals, pins, secret sealing, epoch and audit, and the same rows ({@link SettingsEditor#rows}). Nodes apply a
 * live setting on their next re-read and a restart-only one on their next boot. The catalogue is
 * {@link SettingsContributor#all()}.
 */
public class SettingsAdmin {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+");

    /** How long the orphaned-data snapshot is reused. Its walk can span the whole store, and the diagnostic only counts,
     *  so it tolerates this staleness; the walk runs at most once per window. */
    private static final Duration ORPHAN_TTL = Duration.ofSeconds(60);

    /**
     * How long a {@link CollectedPosture} is reused: the badge rides every console view, so it is collected at most once
     * per tenant per window. Short, as posture is watched while settings change; a change through this console drops
     * the memo, and {@link CollectedPosture#collectedAt()} states the staleness of one made elsewhere.
     */
    private static final Duration POSTURE_TTL = Duration.ofSeconds(10);

    /** How many tenants' posture reports are memoised, since the key is an outside-supplied name; past it the memo is
     *  cleared. */
    private static final int POSTURE_TENANTS = 64;

    private final ArtifactStore root;
    private final UpstreamCredentialSource upstreamCredentials;
    /** The one place a setting is changed, on every surface. */
    private final SettingsEditor editor;
    /** The editor's pin probe, which the screens grey a pinned knob by. */
    private final Function<String, Optional<PinnedSettings.Pin>> pins;
    private final Supplier<List<String>> tenants;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;

    /** The memoised orphaned-data snapshot, guarded by {@link #orphanLock}; deployment-global, for a super-admin
     *  screen. */
    private final Object orphanLock = new Object();
    private Map<String, StorageNamespaces.Report> orphanSnapshot;
    private Instant orphanExpiry = Instant.MIN;
    /** When the memoised snapshot was walked, which the modules screen shows; {@code null} until the first walk. */
    private Instant orphanScannedAt;

    /** The memoised posture per tenant (empty for the deployment-wide view), served to the badge and the screen alike;
     *  dropped on any settings write through this instance. */
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
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<PinnedSettings.Pin>> pins) {
        this(repositoryStore, pins, List::of);
    }

    /** A fixture that records no audit events. */
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<PinnedSettings.Pin>> pins,
                         Supplier<List<String>> tenants) {
        this(repositoryStore, pins, tenants, AuditTrail.none(), () -> null, () -> "console");
    }

    /** A fixture over an editor of its own ({@link SettingsEditor#over}) that pins what {@code pins} answers and
     *  records on {@code audit} under the {@code current} tenant as the {@code actor}. */
    public SettingsAdmin(ArtifactStore repositoryStore, Function<String, Optional<PinnedSettings.Pin>> pins,
                         Supplier<List<String>> tenants, AuditTrail audit, CurrentTenant current, ConsoleActor actor) {
        this(repositoryStore, editor(repositoryStore, pins, audit), tenants, audit, current, actor, _ -> null);
    }

    private static SettingsEditor editor(ArtifactStore store, Function<String, Optional<PinnedSettings.Pin>> pins,
                                         AuditTrail audit) {
        try {
            return SettingsEditor.over(store, pins, audit, _ -> null);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * The console-bound constructor. {@code editor} changes every setting and its pin probe greys a pinned knob;
     * {@code tenants} are the scopes the orphaned-data diagnostic scans; what is not a setting (an upstream credential,
     * a purge) is recorded on {@code audit}. {@code credentialConfig} supplies deploy-time keys such as
     * {@code JENREPO_SECRETS_KEY}, so a credential is sealed under the API's key; without it every credential write is
     * refused.
     */
    public SettingsAdmin(ArtifactStore repositoryStore, SettingsEditor editor, Supplier<List<String>> tenants,
                         AuditTrail audit, CurrentTenant current, ConsoleActor actor,
                         UnaryOperator<String> credentialConfig) {
        this.editor = editor;
        this.root = repositoryStore;
        // The server's discovered source; NONE when the module is absent.
        this.upstreamCredentials = UpstreamCredentialSourceProvider.resolve(repositoryStore, credentialConfig);
        this.pins = editor::pinned;
        this.tenants = tenants;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
    }

    /** Records a privileged mutation under the selected tenant, attributed to the member; best-effort. */
    private void audit(String action, String target) {
        audit.record(current.name(), actor.name(), action, target);
    }

    /** Who this console's session acts as on the audit trail: the selected tenant and the signed-in member. */
    private SettingsEditor.Actor actor() {
        return new SettingsEditor.Actor(current.name(), actor.name());
    }

    /** One value, blank for a clear. */
    private static Map<String, String> one(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value == null ? "" : value);
        return values;
    }

    /** The deployment's settings grouped for the settings screen, from the rows {@code GET /api/settings} answers. */
    public List<Group> groups() throws IOException {
        return levelGroups(editor.rows(Setting.Scope.GLOBAL, null, null), true);
    }

    /** One key's value in force deployment-wide - the operator's pin, else the stored value, else {@code fallback} -
     *  for a console decision that reads a dial rather than rendering it ({@link SettingsEditor#effective}). */
    public String effective(String key, String fallback) {
        return editor.effective(null, key, fallback);
    }

    /** One key's value in force for a tenant - a pin, else the tenant's own where the key is tenant-settable, else the
     *  deployment's stored value, else {@code fallback} ({@link SettingsEditor#effective}). */
    public String effective(String tenant, String key, String fallback) {
        return editor.effective(tenant, key, fallback);
    }

    /** One setting's view: its effective value against {@code baseline} (the default globally, the deployment value in
     *  a tenant view), override, live and pin state, and contributing module. */
    private SettingView view(Setting setting, String effective, String baseline, boolean overridden,
                             Optional<PinnedSettings.Pin> pin, String module) {
        return new SettingView(setting.key(), setting.group(), setting.label(), setting.description(),
                setting.kind().name(), setting.choices(), effective, baseline, overridden, setting.live(),
                highImpact(setting), pin.isPresent(), pin.map(PinnedSettings.Pin::source).orElse(""), module,
                setting.tier() == Setting.Tier.ADVANCED, setting.editedAs().name(),
                setting.choices().stream().map(setting::choice).toList());
    }

    /** The JPMS module a key is attributed to - the contributor that declares it, or {@link SettingsDocuments#NEUTRAL}
     *  for a neutral core dial or a map entry. */
    private static String moduleOf(Map<String, String> attribution, String key) {
        return attribution.getOrDefault(key, SettingsDocuments.NEUTRAL);
    }

    /** The modules console's rows: every discovered module's state, settings and toggle, plus a not-installed row for a
     *  module named only by a stored document or a storage manifest, carrying its orphaned-data counts. */
    public List<ModuleView> modules() throws IOException {
        Properties stored = read();
        Map<String, String> attribution = SettingsContributor.attribution();
        UnaryOperator<String> effective = key -> {
            Optional<PinnedSettings.Pin> pin = pins.apply(key);
            return pin.isPresent() ? pin.get().value() : stored.getProperty(key);
        };
        Set<String> storedModules = new TreeSet<>(editor.settings().documents().keySet());
        Map<String, StorageNamespaces.Report> orphans = orphanedData();
        List<ModuleView> views = new ArrayList<>();
        for (ModuleCapability capability : ModuleCapability.resolve(effective, storedModules)) {
            List<SettingView> settings = new ArrayList<>();
            for (Setting setting : capability.settings()) {
                String override = stored.getProperty(setting.key());
                Optional<PinnedSettings.Pin> pin = pins.apply(setting.key());
                String value = pin.map(PinnedSettings.Pin::value).orElse(override != null ? override : setting.defaultValue());
                settings.add(view(setting, value, setting.defaultValue(), override != null, pin,
                        moduleOf(attribution, setting.key())));
            }
            StorageNamespaces.Report orphan = orphans.remove(capability.module());
            views.add(new ModuleView(capability.module(), capability.installed(), capability.enabled(),
                    capability.gated(), capability.enableKey(), capability.live(), capability.toggleable(), settings,
                    orphan == null ? 0 : orphan.objects(), orphan == null ? 0 : orphan.bytes(),
                    orphan == null ? List.of() : kept(orphan)));
        }
        // A module named only by its storage manifest still gets a row.
        orphans.forEach((module, orphan) -> views.add(new ModuleView(module, false, false, false, null, false,
                false, List.of(), orphan.objects(), orphan.bytes(), kept(orphan))));
        views.sort(Comparator.comparing(ModuleView::module));
        return views;
    }

    /** The plug-in surface grouped by SPI, over the same {@link ModuleCapability} model {@link #modules()} lists per
     *  module, so the two agree on what is on. */
    public List<SpiCatalog> catalog() throws IOException {
        Properties stored = read();
        UnaryOperator<String> effective = key -> {
            Optional<PinnedSettings.Pin> pin = pins.apply(key);
            return pin.isPresent() ? pin.get().value() : stored.getProperty(key);
        };
        Set<String> storedModules = new TreeSet<>(editor.settings().documents().keySet());
        return ModuleCapability.catalog(effective, storedModules);
    }


    /** The posture for a tenant, which the screen renders and the header badge counts: every discovered
     *  {@code SafetyAdvisor}'s advisories against the effective configuration - a pin, over the tenant's document for a
     *  {@link SettingsScopes#tenantOverridable} key, over the deployment document, over the {@code deployment} lookup -
     *  split by {@link ScopedPosture}. The tenant reaches the advisors through {@link TenantPosture#scoped}; a blank one
     *  yields the deployment-wide half. Memoised for {@link #POSTURE_TTL}, keyed by tenant alone, since every caller
     *  passes the console's one environment. */
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

    /** Drops every memoised posture, on each settings write through this console, so its effect shows at once. */
    private void invalidatePosture() {
        postureSnapshots.clear();
    }

    /** The uncached collection: one tenant's effective chain, evaluated by every discovered advisor and split into
     *  what that tenant's view may see. */
    private ScopedPosture collectPosture(String selected, UnaryOperator<String> deployment) throws IOException {
        String prefix = "jenrepo.";
        Properties stored = read();
        Properties overrides = selected.isEmpty() ? new Properties() : read(selected);
        Configuration base = Configuration.of(fullKey -> {
            // The store is keyed by the bare key; any other key is read off the deployment.
            if (fullKey.startsWith(prefix)) {
                String key = fullKey.substring(prefix.length());
                Optional<PinnedSettings.Pin> pin = pins.apply(key);
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

    /** The orphaned-data reports by module, as a mutable copy of the snapshot {@link #modules()} consumes. */
    private Map<String, StorageNamespaces.Report> orphanedData() throws IOException {
        return new LinkedHashMap<>(orphanSnapshot());
    }

    /** The orphaned-data snapshot, re-walked once expired. The walk runs outside {@link #orphanLock}, and a race past the
     *  expiry recomputes an idempotent result. */
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

    /** When the snapshot {@link #modules()} rendered was walked, the screen's staleness line; {@code null} before the
     *  first walk. Read right after {@link #modules()}. */
    public Instant orphanScannedAt() {
        synchronized (orphanLock) {
            return orphanScannedAt;
        }
    }

    /**
     * Purges the named module's declared key-spaces across every tenant, the primitive {@code POST /api/admin/purge}
     * drives, audited the same way, and drops the snapshot. Empty when no manifest entry names the module.
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

    /** The uncached walk: what every not-installed manifest entry's key-spaces hold across the tenants. */
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

    /** Whether a setting gates admission (the compliance verdict knobs and deny lists), so a change is confirmed. */
    private static boolean highImpact(Setting setting) {
        return "Compliance".equals(setting.group())
                && (setting.kind() == Setting.Kind.CHOICE
                        || setting.key().toLowerCase(Locale.ROOT).contains("deny"));
    }

    /** Sets, or with a blank value clears, one deployment-wide override ({@link SettingsEditor#deployment}). */
    public void save(String key, String value) throws IOException {
        editor.deployment(one(key, value), actor());
        invalidatePosture();
    }

    /** The stored settings as the secret-free bundle {@code /api/settings/export} emits ({@link Settings#exportBundle}). */
    public byte[] exportBundle() throws IOException {
        return SettingsDocuments.serializeBundle(editor.settings().exportBundle());
    }

    /** Restores a settings bundle ({@link SettingsEditor#importBundle}), refused whole before any write when a value is
     *  refused or a key pinned. */
    public void importBundle(Map<String, ? extends Map<String, String>> bundle) throws IOException {
        editor.importBundle(bundle, actor());
        invalidatePosture();
    }

    /** A tenant's settings grouped for its screen, from the rows {@code GET /api/settings?tenant=} answers. */
    public List<Group> groups(String tenant) throws IOException {
        return levelGroups(editor.rows(Setting.Scope.TENANT, tenant, null), true);
    }

    /** Sets or clears one of a tenant's overrides ({@link SettingsEditor#tenant}). */
    public void save(String tenant, String key, String value) throws IOException {
        editor.tenant(tenant, one(key, value), true, actor());
        invalidatePosture();
    }

    /** Restores one tenant's slice from a bundle ({@link SettingsEditor#importTenant}). */
    public void importTenant(String tenant, Map<String, ? extends Map<String, String>> bundle) throws IOException {
        editor.importTenant(tenant, bundle, actor());
        invalidatePosture();
    }

    /** The runtime repository definitions (name to routing specification), the {@code repositories.<name>} entries. */
    public Map<String, String> repositories() throws IOException {
        return entries(SettingsScopes.REPOSITORY_PREFIX);
    }

    /** Whether a definition has a hardened upstream ({@code fallback <url> harden}), which screens every fetched body in
     *  full before releasing a byte; a malformed specification reads as not hardened. */
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
     * A repository definition's shape for the badges, parsed by the {@link RepositoryDefinition} the router routes on,
     * reading nothing. Unconfigured or malformed reads as the default: writable, no fallbacks.
     */
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
                        // A `fallback dns redirect` resolves its upstream per request and stores nothing.
                        fallbacks.add(new FallbackBadge(false, "dns", false,
                                screeningLabel(fallback.screening()), null));
            }
        }
        return new RepositoryShape(true, definition.writable(), List.copyOf(fallbacks),
                warnings(definition, specification));
    }

    /** A parsed definition's valid but risky traits, each a notice rather than a refusal: mixed screening strength
     *  ({@link RepositoryDefinition#mixedStrength}), an unscreened upstream, a plaintext one
     *  ({@link RepositoryDefinition#plaintextUpstream}). */
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
     *  screening policy. */
    private static String screeningLabel(RepositoryDefinition.Screening screening) {
        return switch (screening) {
            case DEFAULT -> "default";
            case HARDEN -> "harden";
            case UNSCREENED -> "unscreened";
        };
    }

    /** A repository's parsed shape: whether it is configured, accepts uploads, its ordered fallback badges and its
     *  warnings. */
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

    /** One fallback's badge: an upstream's {@code source}, {@code store} and {@code screening}, or a repository-name
     *  fallback's inner {@code repository}, which owns its own policy. */
    public record FallbackBadge(boolean upstream, String source, boolean store, String screening, String repository) {
    }

    /** Where a repository's routing comes from. */
    public enum Layer {
        /** Nothing defines it: a plain hosted repository. */
        NONE,
        /** The deployment's definition of its name, which every tenant's repository of that name inherits. */
        DEPLOYMENT,
        /** The repository's own {@code routing} setting, over the deployment's definition. */
        REPOSITORY
    }

    /** A repository's routing: the specification in force, the layer it comes from, and the shape it parses to. */
    public record Routing(String specification, Layer layer, RepositoryShape shape) {
    }

    /** A repository's routing: its own {@code routing} setting, one point read, else the deployment's definition. */
    public Routing routing(String tenant, String name) throws IOException {
        return routing(tenant, name, repositories());
    }

    /** {@link #routing(String, String)} against definitions already read, for a list. */
    public Routing routing(String tenant, String name, Map<String, String> deploymentDefinitions) throws IOException {
        if (tenant != null && Scopes.valid(name)) {
            String own = StoredSettings.value(StoredSettings.repository(root, tenant, name),
                    RoutingSettingsContributor.KEY).orElse(null);
            if (own != null && !own.isBlank()) {
                return new Routing(own, Layer.REPOSITORY, shape(name, own));
            }
        }
        String deployment = deploymentDefinitions.get(name);
        return deployment == null || deployment.isBlank()
                ? new Routing("", Layer.NONE, shape(name, null))
                : new Routing(deployment, Layer.DEPLOYMENT, shape(name, deployment));
    }

    /** Stores the deployment's definition of a repository name ({@link SettingsEditor#definition}), routing every
     *  tenant's repository of that name without its own; {@code tenant} must be {@code null}. */
    public void setRepository(String tenant, String name, String specification) throws IOException {
        if (tenant != null) {
            throw new IllegalArgumentException("A tenant's repository is routed by its own routing setting, not by a "
                    + "tenant definition.");
        }
        editor.definition(name, specification, actor());
        invalidatePosture();
    }

    /** Remove the deployment's definition of a repository name; {@code tenant} must be {@code null}, as for
     *  {@link #setRepository}. */
    public void removeRepository(String tenant, String name) throws IOException {
        if (tenant != null) {
            throw new IllegalArgumentException("A tenant's repository is routed by its own routing setting, not by a "
                    + "tenant definition.");
        }
        editor.definition(name, null, actor());
        invalidatePosture();
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
     * The public registries of the installed formats with no upstream named yet, which the console offers in one click,
     * since a format fetches nothing until one is named.
     */
    public Map<String, String> suggestedUpstreams() throws IOException {
        Map<String, String> named = upstreams();
        Map<String, String> suggested = new TreeMap<>(PUBLIC_REGISTRIES);
        suggested.keySet().removeAll(named.keySet());
        return suggested;
    }

    /** Sets a format's upstream, the deployment's or a tenant's ({@link SettingsEditor#upstream}), screened as every
     *  outbound target is. */
    public void setUpstream(String tenant, String format, String url) throws IOException {
        editor.upstream(tenant, format, url, actor());
        invalidatePosture();
    }

    public void removeUpstream(String tenant, String format) throws IOException {
        editor.upstream(tenant, format, null, actor());
        invalidatePosture();
    }

    /** The upstreams {@code tenant} set for itself (format to URL), which its repositories pull through over the
     *  deployment's. */
    public Map<String, String> upstreams(String tenant) throws IOException {
        Properties own = read(tenant);
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : own.stringPropertyNames()) {
            if (key.startsWith(SettingsScopes.UPSTREAM_PREFIX)) {
                entries.put(key.substring(SettingsScopes.UPSTREAM_PREFIX.length()), own.getProperty(key));
            }
        }
        return entries;
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

    /** The upstream hosts carrying a write-only proxy credential; only the hosts. */
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


    /** The deployment's stored values, as the node holds them - the read {@code GET /api/settings} makes. */
    private Properties read() {
        return properties(editor.settings().overrides());
    }

    /** A tenant's own stored values, as the node holds them. */
    private Properties read(String tenant) {
        return properties(editor.settings().overrides(tenant));
    }

    private static Properties properties(Map<String, String> values) {
        Properties properties = new Properties();
        properties.putAll(values);
        return properties;
    }

    /**
     * Sets each of {@code values} deployment-wide in one batch ({@link SettingsEditor#deployment}); one refused value
     * writes none. A blank value clears.
     */
    public void saveAll(Map<String, String> values) throws IOException {
        editor.deployment(values, actor());
        invalidatePosture();
    }

    /**
     * Why each of {@code values} cannot be stored at {@code level} ({@link SettingsEditor#refusals}), asked by a wizard
     * on leaving a step.
     */
    public SortedMap<String, String> refusals(Setting.Scope level, Map<String, String> values, boolean operator) {
        return editor.refusals(level, values, operator);
    }

    /**
     * Why {@code value} would be refused for the setting {@code key}, judged at the setting's own level by the same
     * refusals a save makes, or empty when it would be taken - what a form asks as soon as a field is left. An empty
     * value is taken: it inherits.
     */
    public Optional<String> check(String key, String value) {
        Optional<Setting> setting = catalogue().stream().filter(declared -> declared.key().equals(key)).findFirst();
        if (setting.isEmpty()) {
            return Optional.of("No setting is called '" + key + "'.");
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(editor.refusals(setting.get().scope(), Map.of(key, value), true).get(key));
    }

    /**
     * A repository's effective configuration ({@link SettingsEditor#config}), the chain the server resolves, so a preview
     * judges by a sweep's policy.
     */
    public UnaryOperator<String> repositoryConfig(String tenant, String repository) throws IOException {
        return editor.config(Setting.Scope.REPOSITORY, tenant, repository);
    }

    /** A build-cache project's effective configuration, as {@link #repositoryConfig} is a repository's. */
    public UnaryOperator<String> projectConfig(String tenant, String project) throws IOException {
        return editor.config(Setting.Scope.PROJECT, tenant, project);
    }

    /** Each of a tenant's projects' effective configuration, for a list. */
    public ProjectConfigs projectConfigs(String tenant) {
        return project -> editor.config(Setting.Scope.PROJECT, tenant, project);
    }

    /** A project's effective configuration, by project name - see {@link #projectConfigs}. */
    @FunctionalInterface
    public interface ProjectConfigs {
        UnaryOperator<String> of(String project) throws IOException;
    }

    /**
     * A repository's settings grouped for its screen ({@link SettingsEditor#rows}). An
     * {@link Setting#operatorOnly() operator-only} setting is shown fixed to a session that is not the operator.
     */
    public List<Group> repositoryGroups(String tenant, String repository, boolean operator) throws IOException {
        return levelGroups(editor.rows(Setting.Scope.REPOSITORY, tenant, repository), operator);
    }

    /** A build-cache project's settings, grouped for its screen, as {@link #repositoryGroups} are a repository's. */
    public List<Group> projectGroups(String tenant, String project) throws IOException {
        return levelGroups(editor.rows(Setting.Scope.PROJECT, tenant, project), true);
    }

    /**
     * The settings {@code wizard} asks, keyed by setting: the deployment's rows for the first boot, and for a new
     * repository or project what it would inherit. An operator-only setting is shown fixed to anyone else.
     */
    public Map<String, SettingView> wizardViews(Wizard wizard, String tenant, boolean operator) throws IOException {
        List<Group> groups = switch (wizard) {
            case SETUP -> groups();
            case REPOSITORY -> levelGroups(editor.rows(Setting.Scope.REPOSITORY, tenant, null), operator);
            case PROJECT -> levelGroups(editor.rows(Setting.Scope.PROJECT, tenant, null), true);
        };
        Map<String, SettingView> views = new LinkedHashMap<>();
        for (Group group : groups) {
            for (SettingView view : group.settings()) {
                views.put(view.key(), view);
            }
        }
        return views;
    }

    private List<Group> levelGroups(List<SettingsEditor.Row> rows, boolean operator) {
        Map<String, String> attribution = SettingsContributor.attribution();
        Map<String, List<SettingView>> grouped = new LinkedHashMap<>();
        for (SettingsEditor.Row row : rows) {
            Setting setting = row.setting();
            Optional<PinnedSettings.Pin> pin = row.pin();
            if (pin.isEmpty() && !operator && setting.operatorOnly()) {
                pin = Optional.of(new PinnedSettings.Pin("the deployment's operator", row.effective()));
            }
            // A tenant's row does not read a secret's deployment value back, so it inherits nothing to show.
            String effective = Objects.requireNonNullElse(pin.map(PinnedSettings.Pin::value).orElse(row.effective()),
                    "");
            grouped.computeIfAbsent(setting.group(), _ -> new ArrayList<>())
                    .add(view(setting, effective, Objects.requireNonNullElse(row.inherited(), ""), row.overridden(),
                            pin, moduleOf(attribution, setting.key())));
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((name, settings) -> groups.add(new Group(name, settings)));
        return groups;
    }

    /**
     * Sets each non-blank and clears each blank value in a repository's own documents ({@link SettingsEditor#repository}),
     * refused whole when any value is. {@code operator} is whether the session is the deployment's operator.
     */
    public void saveRepository(String tenant, String repository, Map<String, String> values, boolean operator)
            throws IOException {
        editor.repository(tenant, repository, values, operator, actor());
    }

    /** {@link #saveRepository} for a build-cache project's own documents ({@link SettingsEditor#project}). */
    public void saveProject(String tenant, String project, Map<String, String> values) throws IOException {
        editor.project(tenant, project, values, actor());
    }

    /** The editable-settings catalogue. */
    private List<Setting> catalogue() {
        return SettingsContributor.all();
    }

    /** One area's settings, for a section on the form. */
    public record Group(String name, List<SettingView> settings) {

        /** The settings an operator is expected to decide, which the section shows. */
        public List<SettingView> essentials() {
            return settings.stream().filter(setting -> !setting.advanced()).toList();
        }

        /** The settings that tune what was decided, which the section folds behind a disclosure. */
        public List<SettingView> tuning() {
            return settings.stream().filter(SettingView::advanced).toList();
        }

        /** How many of the folded settings carry a value of their own, which the disclosure says. */
        public long tuningChanged() {
            return tuning().stream().filter(SettingView::overridden).count();
        }
    }

    /**
     * One collected posture and its as-of instant, handed to the screen and the badge alike.
     */
    public record CollectedPosture(ScopedPosture posture, Instant collectedAt) {

        public CollectedPosture {
            Objects.requireNonNull(posture, "posture");
            Objects.requireNonNull(collectedAt, "collectedAt");
        }
    }

    /** One module's row on the modules console, as {@link ModuleCapability} resolves it, with the orphaned-data counts
     *  of a not-installed module whose key-spaces still hold data. */
    public record ModuleView(String module, boolean installed, boolean enabled, boolean gated, String enableKey,
                             boolean live, boolean toggleable, List<SettingView> settings,
                             long orphanObjects, long orphanBytes, List<String> orphanKept) {

        public ModuleView {
            settings = List.copyOf(settings);
            orphanKept = List.copyOf(orphanKept);
        }

        /** Whether a toggle applies only on restart, shown as a {@code restart} badge. */
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

    /** What a purge of a removed module leaves because installed modules declare it too, as the screen says it. */
    private static List<String> kept(StorageNamespaces.Report report) {
        return report.kept().stream()
                .map(kept -> kept.prefix() + " (" + kept.scope() + ", also " + String.join(", ", kept.owners()) + ")")
                .toList();
    }

    /** One setting as the settings screens render it: documentation, kind and choices, effective and default value,
     *  override, live, high-impact and pin state, contributing module and tier. The helpers keep the template free of
     *  logic. */
    public record SettingView(String key, String group, String label, String description,
                              String kind, List<String> choices, String value, String defaultValue,
                              boolean overridden, boolean live, boolean highImpact,
                              boolean pinned, String pinnedBy, String module, boolean advanced, String form,
                              List<Setting.Choice> named) {

        public SettingView {
            choices = List.copyOf(choices);
            form = form == null ? Setting.Form.LINE.name() : form;
            named = named == null ? List.of() : List.copyOf(named);
        }

        /** The name and short description a choice reads as, as the setting names it or from its value. */
        public Setting.Choice choiceOf(String option) {
            return named.stream().filter(choice -> choice.value().equals(option)).findFirst()
                    .orElseGet(() -> new Setting.Choice(option, Setting.Choice.nameOf(option), ""));
        }

        /** What the value in force does, as its choice describes it - "Hold for review: ...", the long form a field
         *  shows under a drop-down - or empty where the choice has no description. */
        public String choiceDescription() {
            Setting.Choice choice = choiceOf(value);
            return choice.description().isBlank() ? "" : choice.name() + ": " + choice.description();
        }

        /** Whether a form edits the value over several lines - free text, a list one entry per line, or JSON. */
        public boolean textArea() {
            return "TEXT".equals(form) || "LINES".equals(form) || "JSON".equals(form);
        }

        /** Whether the value is several values on one line, which a form edits as one removable entry each, added
         *  from the {@link #options() values the setting knows}. */
        public boolean values() {
            return "VALUES".equals(form);
        }

        /** Whether the value is a JSON document, which a form edits in a fixed-width face. */
        public boolean json() {
            return "JSON".equals(form);
        }

        /** Whether the value is a repository's routing, which a form edits as its clauses. */
        public boolean routing() {
            return "ROUTING".equals(form);
        }

        /** Whether the value is a duration, which a form edits as an amount and a unit. */
        public boolean duration() {
            return "DURATION".equals(kind) || "DURATION_OR_NONE".equals(kind);
        }

        /** Whether a duration may be switched off ({@link Setting#NONE}), which a form offers as "never". */
        public boolean durationOrNone() {
            return "DURATION_OR_NONE".equals(kind);
        }

        /** A CHOICE renders as a select of its catalogued options; a BOOLEAN as a {@link #toggle() switch}; every
         *  other kind as a typed text input. */
        public boolean dropdown() {
            return "CHOICE".equals(kind);
        }

        /** A BOOLEAN renders as a switch showing its effective value, saving the opposite in one click. */
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

        /** The effective value for display: a set secret is masked (whether stored or pinned), a boolean reads as
         *  {@code enabled} or {@code disabled}, an empty value as {@code (unset)}. */
        public String effectiveDisplay() {
            if (secret() && (overridden || pinned)) {
                return "••••••";
            }
            return display(value);
        }

        /** The default for display, as {@link #effectiveDisplay()} reads a value. */
        public String defaultDisplay() {
            return display(defaultValue);
        }

        /** What a {@link #dropdown()} option reads as: the value as {@link #effectiveDisplay()} would show it, marked
         *  {@code (default)} after it where it is the default - "enabled (default)". */
        public String optionLabel(String option) {
            return option.equals(defaultValue) ? display(option) + " (default)" : display(option);
        }

        /** The description of {@code option}, which an option carries as its title; empty where it has none. */
        public String optionDescription(String option) {
            return choiceOf(option).description();
        }

        private String display(String raw) {
            if (raw.isBlank()) {
                return "(unset)";
            }
            if ("BOOLEAN".equals(kind)) {
                return Boolean.parseBoolean(raw.trim()) ? "enabled" : "disabled";
            }
            if ("CHOICE".equals(kind)) {
                return choiceOf(raw.trim()).name();
            }
            if (duration()) {
                return DurationWords.describe(raw);
            }
            return raw;
        }

        /** The edit control's prefill: the current override, but never a secret's, which a masked field would still
         *  carry in the page source. */
        public String editValue() {
            return secret() || !overridden ? "" : value;
        }

        /** The edit control's placeholder: for a secret only whether it is set, otherwise the default, marked as one
         *  after it. */
        public String editPlaceholder() {
            if (secret()) {
                return overridden ? "(set — re-enter to change)" : "(unset)";
            }
            return defaultValue.isBlank() ? "(unset)" : display(defaultValue) + " (default)";
        }
    }
}
