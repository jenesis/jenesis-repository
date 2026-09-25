package build.jenesis.repository.config.web;

import module java.base;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.FirstRunSteps;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.upstream.UpstreamCredential;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deployment-config management surface - the runtime-editable settings catalogue, the runtime repository
 * definitions, the per-format proxy upstreams and the per-host upstream credentials - peeled out of the
 * {@code RepositoryController} monolith into its own thin {@code web} adapter and contributed through the
 * {@code ServerModuleProvider} seam. A JSON CRUD over the store-backed {@link Settings} (with {@link LiveConfig}
 * rebuilt live where a setting allows it) and the discovered {@link UpstreamCredentialSource}; the tenant is the one
 * carried by the managing {@code Jenesis-Repository-Key} header, resolved through {@link Repositories}.
 * These are deployment-wide knobs, so every route here is under {@code /api/} and is gated {@code manage:write} (the
 * mutations) or {@code manage:read} (the reads) at scope {@code *} by the security chain before the request is
 * reached - operator-tenant-only - so this controller makes no authorization decision, the same guard the monolith
 * carried, unchanged by the move. With no upstream-credential module installed the {@code /api/upstreams/auth}
 * endpoints answer {@code 501}, after the auth check so {@code 401}/{@code 403} still precede. A privileged mutation
 * writes an audit event.
 */
@RestController
public class ConfigController {

    private final Repositories repositories;
    private final Settings settings;
    private final LiveConfig live;
    private final PinnedSettings pinnedSettings;
    private final UpstreamCredentialSource upstreamCredentials;
    private final AuditTrail audit;
    private final RepositoryRouting routing;
    // The set of settings a deployment's installed modules contribute is static for the JVM, so the catalogue is
    // discovered once rather than re-running the ServiceLoader scan (and re-sorting) on every /api/settings read/write;
    // a setting's live effective value and override state are resolved per request against this fixed template below.
    private final List<Setting> catalogue = SettingsContributor.all();

    public ConfigController(Repositories repositories, Settings settings, LiveConfig live,
                            PinnedSettings pinnedSettings, UpstreamCredentialSource upstreamCredentials,
                            AuditTrail audit, RepositoryRouting routing) {
        this.repositories = repositories;
        this.settings = settings;
        this.live = live;
        this.pinnedSettings = pinnedSettings;
        this.upstreamCredentials = upstreamCredentials;
        this.audit = audit;
        this.routing = routing;
    }

    /** The deployment's {@code proxy-allow-internal} dial, read through {@link LiveConfig} so this write surface and
     *  the boot sweep answer from exactly the same effective value (stored setting over file/env default). */
    private boolean allowInternal() {
        return live.proxyAllowInternal();
    }

    private void audit(String key, String action, String target) {
        audit(repositories.tenant(key), key, action, target);
    }

    private void audit(String tenant, String key, String action, String target) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    /** The deployment-wide runtime settings: each editable key with its effective value, its file/env default,
     *  whether a stored override is in force, and whether it is pinned from a source above the store (with the phrase
     *  naming what pins it) - a pinned key ignores the store, so a client greys the knob and a write is refused. The
     *  settings that cannot change at runtime (storage backend, listen port, whether auth is enforced) are not
     *  listed. */
    @GetMapping("/api/settings")
    @ResponseBody
    public List<SettingView> settings(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestParam(value = "tenant", required = false) String tenant) {
        if (tenant != null && !tenant.isBlank()) {
            return tenantSettings(tenant);
        }
        Map<String, String> overrides = settings.overrides();
        List<SettingView> view = new ArrayList<>();
        for (Setting setting : catalogue()) {
            String override = overrides.get(setting.key());
            Optional<PinnedSettings.Pin> pin = pinnedSettings.pinned(setting.key());
            // A pinned key resolves to the operator's pin, not the store: report the pin's value as effective and
            // the store override (if any) as inert.
            String effective = pin.map(PinnedSettings.Pin::value)
                    .orElse(override != null ? override : setting.defaultValue());
            view.add(view(setting, effective, setting.defaultValue(), override != null, pin));
        }
        return view;
    }

    /** A tenant's runtime-settings view: only the tenant-overridable keys (the gate policy, deny list and forward
     *  targets a tenant may retune), each with its tenant-effective value along the chain <em>pin &gt; tenant document
     *  &gt; global document &gt; default</em>, its global effective value as the tenant's baseline, whether this tenant
     *  has overridden it, and whether it is pinned deployment-wide (a pinned key is inert for a tenant too). The
     *  deployment-wide knobs are not listed - a tenant cannot change them. */
    /**
     * The first-run setup guide - the decisions a new deployment should make, in order, each with the settings
     * rows it is about: the one list the console's {@code /setup} screen and the CLI's {@code setup} verb render
     * ({@link FirstRunSteps}), so a step is a capability on all three surfaces and not a screen. A key the catalogue
     * does not carry is left out of its step, and a step left with none is left out of the guide, as on the
     * console's Setup screen. Writes go through {@code PUT /api/settings/<key>} like any other.
     * Its one store read is the settings document {@code GET /api/settings} reads - one object per module under a
     * constant prefix, narrow by construction.
     */
    @GetMapping("/api/setup")
    @ResponseBody
    public SetupView setup(@RequestHeader(value = Repositories.KEY, required = false) String key) {
        Map<String, SettingView> rows = new LinkedHashMap<>();
        for (SettingView row : settings(key, null)) {
            rows.put(row.key(), row);
        }
        List<StepView> steps = new ArrayList<>();
        for (FirstRunSteps.Step step : FirstRunSteps.ALL) {
            List<SettingView> carried = new ArrayList<>();
            for (String named : step.keys()) {
                SettingView row = rows.get(named);
                if (row != null) {
                    carried.add(row);
                }
            }
            if (carried.isEmpty() && !step.id().equals(FirstRunSteps.STARTER_CREDENTIAL)) {
                continue;
            }
            steps.add(new StepView(step.id(), step.title(), step.why(), carried));
        }
        return new SetupView(steps);
    }

    private List<SettingView> tenantSettings(String tenant) {
        Map<String, String> tenantOverrides = settings.overrides(tenant);
        List<SettingView> view = new ArrayList<>();
        for (Setting setting : catalogue()) {
            if (!SettingsScopes.tenantOverridable(setting.key())) {
                continue;
            }
            String tenantValue = tenantOverrides.get(setting.key());
            // A SECRET's baseline is never read back (view() nulls it), so do not decrypt it here: resolving a stored
            // secret only to discard it would needlessly fail-closed and 500 this view when the value cannot be
            // decrypted. Its presence still shows through the raw tenant-override map below.
            String globalEffective = setting.kind() == Setting.Kind.SECRET
                    ? null
                    : settings.getOrDefault(setting.key(), setting.defaultValue());
            Optional<PinnedSettings.Pin> pin = pinnedSettings.pinned(setting.key());
            String effective = pin.map(PinnedSettings.Pin::value)
                    .orElse(tenantValue != null ? tenantValue : globalEffective);
            view.add(view(setting, effective, globalEffective, tenantValue != null, pin));
        }
        return view;
    }

    /** Build one setting's API view carrying its {@link Setting.Kind kind}. A SECRET value is <em>never</em> emitted -
     *  null on read (write-only semantics), so neither the stored value nor, in a tenant view, the global baseline
     *  leaks into the JSON; {@code overridden}/{@code pinned} still signal <em>whether</em> it is set, and a client
     *  changes it by writing a new value through {@code set}, never by reading the current one. This is the one server
     *  chokepoint the console's own masking never covered - the API path bypassed it. Every other kind carries its
     *  effective value and baseline as before. */
    private static SettingView view(Setting setting, String effective, String baseline, boolean overridden,
                                    Optional<PinnedSettings.Pin> pin) {
        boolean secret = setting.kind() == Setting.Kind.SECRET;
        return new SettingView(setting.key(), setting.kind().name(),
                secret ? null : effective, secret ? null : baseline, overridden, setting.live(),
                pin.isPresent(), pin.map(PinnedSettings.Pin::source).orElse(""),
                setting.group(), setting.label(), setting.description());
    }

    /** Set a runtime override for one editable setting; unknown keys are rejected so only the catalogued settings
     *  change. A live setting takes effect at once on this node ({@code live.rebuild()}); the rest apply on restart. A
     *  value that a live setting cannot parse (a bad severity, duration or number) is rolled back and refused with
     *  {@code 400}, so it neither persists nor wedges the running configuration. */
    @PutMapping("/api/settings/{key}")
    public void setSetting(@PathVariable("key") String key,
                           @RequestHeader(value = Repositories.KEY, required = false) String authKey,
                           @RequestParam(value = "tenant", required = false) String tenant,
                           @RequestBody SettingRequest request, HttpServletResponse response) throws IOException {
        Setting setting = catalogue().stream().filter(candidate -> candidate.key().equals(key))
                .findFirst().orElse(null);
        if (setting == null) {
            response.setStatus(400);
            return;
        }
        Optional<PinnedSettings.Pin> pin = pinnedSettings.pinned(key);
        if (pin.isPresent()) {
            // The operator has fixed this key from above the store (env var, -D, command line or an external config
            // file); a stored value would be inert, so refuse the write rather than persist a lie.
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("setting '" + key + "' is pinned by " + pin.get().source()
                    + " and cannot be changed; the stored value would be inert");
            return;
        }
        String value = request == null ? null : request.value();
        if (value != null && !value.isBlank() && !setting.parses(value)) {
            response.setStatus(400);
            return;
        }
        if (tenant != null && !tenant.isBlank()) {
            // A per-tenant override: refused for a deployment-wide key by Settings.set(tenant, ...) - a tenant retunes
            // only its own gate policy, deny list or forward target. No global rebuild - a tenant gate is resolved on
            // demand from the freshly invalidated tenant snapshot. Dry-resolve the tenant gate with the candidate
            // first, though: a kind-valid but plugin-rejected value (a malformed policy expression) would otherwise
            // persist and then 500 every one of that tenant's publishes until cleared - the same keep-last-good guard
            // the deployment-wide branch below gets from live.rebuild()'s rollback.
            try {
                live.validateTenant(tenant, key, value);
            } catch (RuntimeException _) {
                response.setStatus(400);
                return;
            }
            try {
                settings.set(tenant, key, value);
            } catch (IllegalStateException refused) {
                refuseSecret(response, refused);
                return;
            }
            audit(authKey, "setting.set", tenant + "/" + key);
            response.setStatus(200);
            return;
        }
        String previous = settings.overrides().get(key);
        try {
            settings.set(key, value);
        } catch (IllegalStateException refused) {
            // A SECRET write with no master key configured is refused (§9), naming the remedy; nothing was persisted.
            refuseSecret(response, refused);
            return;
        }
        try {
            live.rebuild();
        } catch (RuntimeException _) {
            settings.set(key, previous);
            live.rebuild();
            response.setStatus(400);
            return;
        }
        audit(authKey, "setting.set", key);
        response.setStatus(200);
    }

    /** Clear a runtime override, reverting the setting to its file/env default (or, with a {@code tenant}, to the
     *  deployment-wide value the tenant was overriding). Runs the same guards its {@link #setSetting PUT twin} does:
     *  an unknown key is refused with {@code 400}, so only catalogued settings clear - the {@code repositories.*} and
     *  {@code format-upstream.*} map entries have their own {@code DELETE} routes and are audited as a repository /
     *  upstream removal, not mislabelled as a plain {@code setting.clear} through this catch-all - and a pinned key is
     *  refused with {@code 409}, since its stored value is inert and there is nothing to clear. */
    @DeleteMapping("/api/settings/{key}")
    public void clearSetting(@PathVariable("key") String key,
                             @RequestHeader(value = Repositories.KEY, required = false) String authKey,
                             @RequestParam(value = "tenant", required = false) String tenant,
                             HttpServletResponse response) throws IOException {
        boolean catalogued = catalogue().stream().anyMatch(candidate -> candidate.key().equals(key));
        if (!catalogued) {
            response.setStatus(400);
            return;
        }
        Optional<PinnedSettings.Pin> pin = pinnedSettings.pinned(key);
        if (pin.isPresent()) {
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("setting '" + key + "' is pinned by " + pin.get().source()
                    + " and cannot be changed; the stored value would be inert");
            return;
        }
        if (tenant != null && !tenant.isBlank()) {
            settings.set(tenant, key, null);
            audit(authKey, "setting.clear", tenant + "/" + key);
            response.setStatus(200);
            return;
        }
        settings.set(key, null);
        live.rebuild();
        audit(authKey, "setting.clear", key);
        response.setStatus(200);
    }

    /** Dump the stored settings as one JSON bundle, for backup or transfer to another deployment (the {@code }
     *  export/import pattern). With no {@code tenant} the bundle carries the deployment-wide (global) documents keyed by
     *  module plus every tenant's slice keyed {@code tenant:<tenant>:<module>} (the superadmin view); with a
     *  {@code tenant} it carries only that tenant's documents keyed by module (a tenant slice). Credential-free by
     *  construction: every SECRET-kind key is excluded from the bundle (a stored secret - the keyless identity token -
     *  never travels in a backup), and the write-only upstream credentials are kept out of {@code config/settings}
     *  entirely, so this dump carries no credential. The exclusion is done in {@link Settings#exportBundle} /
     *  {@link Settings#documents(String)} through {@code SettingsSecrets}, not assumed. */
    @GetMapping("/api/settings/export")
    public void exportSettings(@RequestParam(value = "tenant", required = false) String tenant,
                               HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json");
        SortedMap<String, SortedMap<String, String>> bundle = tenant == null || tenant.isBlank()
                ? settings.exportBundle()
                : settings.documents(tenant);
        response.getOutputStream().write(SettingsDocuments.serializeBundle(bundle));
    }

    /** Restore a settings bundle produced by {@link #exportSettings}: parsed with the framework's JSON reader (never
     *  the internal flat-document codec), validated first by a dry {@link LiveConfig} resolve - a malformed value is
     *  refused with {@code 400} before anything is written, so a bad bundle never wedges the running configuration -
     *  then written document-by-document through the store's compare-and-set. With no {@code tenant} it is a full
     *  restore of the deployment-wide documents and every tenant slice (a document the bundle omits is cleared, and a
     *  global-only key in a tenant slice is refused); with a {@code tenant} only that tenant's slice is restored,
     *  leaving the global settings and other tenants untouched. A live setting takes effect at once on this node; the
     *  rest apply on the nodes' next restart. */
    @PostMapping("/api/settings/import")
    public void importSettings(@RequestHeader(value = Repositories.KEY, required = false) String authKey,
                               @RequestParam(value = "tenant", required = false) String tenant,
                               @RequestBody(required = false) Map<String, Map<String, String>> bundle,
                               HttpServletResponse response) throws IOException {
        if (bundle == null) {
            response.setStatus(400);
            return;
        }
        try {
            if (tenant == null || tenant.isBlank()) {
                validateBundle(bundle);
            } else {
                validateTenantSlice(bundle);
            }
        } catch (RuntimeException _) {
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("the settings bundle does not resolve to a valid configuration");
            return;
        }
        // A pinned key is refused here on the same terms PUT and DELETE refuse it. Without this the bundle restore
        // was the one write that could persist a value the operator has fixed from above the store - inert by
        // construction, and the way a pinned-and-stored pair became reachable inside a single boot rather than only
        // across a redeploy. Refusing names every offending key at once, because a restore is one operation and
        // failing it one key at a time would have an operator edit and re-post the bundle repeatedly.
        List<String> pinned = bundle.values().stream()
                .flatMap(document -> document.keySet().stream())
                .distinct()
                .filter(key -> pinnedSettings.pinned(key).isPresent())
                .sorted()
                .toList();
        if (!pinned.isEmpty()) {
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("the bundle sets " + pinned.size() + " setting(s) this deployment pins from "
                    + "above the store, whose stored values would be inert: " + String.join(", ", pinned)
                    + ". Remove them from the bundle, or unpin them where they are pinned.");
            return;
        }
        try {
            if (tenant == null || tenant.isBlank()) {
                settings.importBundle(bundle);
            } else {
                settings.importTenant(tenant, bundle);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // IllegalStateException covers a restored SECRET the deployment cannot encrypt at rest (§9, no master key);
            // nothing was persisted, and the message names the remedy.
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(e.getMessage());
            return;
        }
        live.rebuild();
        audit(authKey, "settings.import", tenant == null || tenant.isBlank()
                ? SettingsDocuments.ROOT
                : tenant + "/" + SettingsDocuments.ROOT);
        response.setStatus(200);
    }

    /** Dry-resolve a full-restore bundle scope by scope: the deployment-wide (global) documents on their own, then
     *  each tenant slice overlaid on that global. A single flattened candidate (last-put-wins) would let a valid
     *  tenant override of a key mask a <em>bad global</em> value of the same key - the flattened resolve validates
     *  clean, yet the import persists the bad global and wedges every boot. Validating each scope distinctly catches
     *  the bad global (and a bad tenant value) before {@link Settings#importBundle} writes anything. Throws on a value
     *  that does not resolve; nothing is assigned. */
    private void validateBundle(Map<String, Map<String, String>> bundle) {
        Map<String, String> global = new LinkedHashMap<>();
        Map<String, Map<String, String>> perTenant = new LinkedHashMap<>();
        bundle.forEach((key, document) -> {
            if (document == null) {
                return;
            }
            if (SettingsDocuments.isTenantKey(key)) {
                String[] parsed = SettingsDocuments.parseTenantKey(key);
                if (parsed == null) {
                    return;   // an unsafe tenant key is rejected with its own message by the persist below
                }
                Map<String, String> slice = perTenant.computeIfAbsent(parsed[0], _ -> new LinkedHashMap<>());
                document.forEach((setting, value) -> {
                    if (value != null) {
                        slice.put(setting, value);
                    }
                });
            } else {
                document.forEach((setting, value) -> {
                    if (value != null) {
                        global.put(setting, value);
                    }
                });
            }
        });
        live.validate(global);
        perTenant.values().forEach(slice -> {
            Map<String, String> combined = new LinkedHashMap<>(global);
            combined.putAll(slice);   // the tenant's overridable keys win over the global baseline, as the gate resolves
            live.validate(combined);
        });
    }

    /** Dry-resolve a tenant-slice import (the {@code ?tenant=} path): the tenant's module documents overlaid on the
     *  deployment-wide overrides the tenant layers over, so the tenant's would-be gate is resolved as it will serve.
     *  Throws on a value that does not resolve; nothing is assigned. */
    private void validateTenantSlice(Map<String, Map<String, String>> bundle) {
        Map<String, String> combined = new LinkedHashMap<>(settings.overrides());
        bundle.values().forEach(document -> {
            if (document != null) {
                document.forEach((setting, value) -> {
                    if (value != null) {
                        combined.put(setting, value);
                    }
                });
            }
        });
        live.validate(combined);
    }

    /** The repositories defined at runtime ({@code repositories.<name>} in the settings store): each name with its
     *  routing specification ({@code hosted} | {@code proxy <url> [nocache] [harden]} | {@code group a,b} | one or
     *  more {@code writable} / {@code fallback <source>} clauses). They add to
     *  or override the deployment's file-configured repositories ({@code jenreg.repositories.<name>}) and
     *  route on the next request. */
    @GetMapping("/api/repositories")
    @ResponseBody
    public List<NamedValue> repositoryDefinitions() {
        return stored(SettingsScopes.REPOSITORY_PREFIX);
    }

    @PutMapping("/api/repositories/{name}")
    public void setRepositoryDefinition(@PathVariable("name") String name,
                                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                                        @RequestBody NamedValueRequest request,
                                        HttpServletResponse response) throws IOException {
        if (!Repositories.valid(name) || request == null || request.value() == null) {
            response.setStatus(400);
            return;
        }
        // Write-time validation (item 1, PRINCIPLES §9): run the SAME parser the boot sweep (
        // LiveConfig.sweepDefinitions) uses BEFORE the definition is stored, so a broken definition never reaches the
        // store. The refusal is LOUD and NAMED - the repository, what is wrong, and the fix - not a bare 400: the
        // parse remedy is surfaced verbatim so the operator can correct it. The parser accepts the new clause grammar
        // (writable / fallback <source> [nocache|harden|unscreened]) and the legacy hosted/proxy/group spellings (they
        // desugar). A valid-but-risky definition (unscreened/plaintext/mixed-strength) parses - its warning is logged
        // and surfaced by the console banner, not refused here.
        RepositoryDefinition definition;
        try {
            definition = RepositoryDefinition.parse(request.value());
        } catch (RuntimeException invalid) {
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Repository '" + name + "' has an invalid definition '" + request.value()
                    + "': " + invalid.getMessage() + " Fix the definition (writable / fallback <source> "
                    + "[nocache|harden|unscreened], or a legacy hosted/proxy/group spelling), or remove it - a "
                    + "repository definition that cannot be parsed is refused rather than stored.");
            return;
        }
        // a plaintext upstream is REFUSED here, not stored with a warning. It is an operator-configured
        // outbound target carrying this deployment's per-host upstream credential, and every peer target (webhook,
        // forward, emulator, redirect rule, import) refuses one; the proxy upstream was the odd one out. The dial is
        // the same proxy-allow-internal the legs read for upstream-advertised URLs, so a deployment with a
        // plaintext internal mirror opts out once for both.
        String refused = RepositoryDefinition.upstreamRefusal(definition, allowInternal());
        if (refused != null) {
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Repository '" + name + "' has a refused definition '" + request.value()
                    + "': " + refused + "." + RepositoryDefinition.upstreamRemedy());
            return;
        }
        settings.set(SettingsScopes.repositoryKey(name), request.value());
        audit(key, AuditActions.REPOSITORY_SET, name);
        response.setStatus(200);
    }

    /**
     * Create a repository to hold one format - {@code PUT /repository/<tenant>/<name>} with
     * {@code {"value":"<format>"}} - in the tenant the deployment's routing decides for the URL, through the one creation every surface makes
     * ({@link RepositoryType#create}). A repository that holds content but no format is given this one, and one whose
     * type the requested one holds everything of - {@code maven} asked to be {@code java} - is given the requested
     * one. Answers {@code 201} when created, {@code 200} when it already held that type or was given it, {@code 409}
     * when it holds a type the requested one does not cover or is being deleted, and {@code 400} for a type no
     * repository can hold here.
     *
     * <p>A {@code "description"} beside the format gives the repository that description - empty clears it - and a
     * description alone, with no format, describes a repository that exists: {@code 200}, or {@code 404} when there is
     * none.
     */
    @PutMapping("/repository/{tenant}/{name}")
    public void createRepository(@PathVariable("name") String name,
                                 @RequestHeader(value = Repositories.KEY, required = false) String key,
                                 @RequestBody RepositoryRequest request,
                                 HttpServletRequest servlet, HttpServletResponse response) throws IOException {
        String format = request == null ? null : request.value();
        String description = request == null ? null : request.description();
        if (description != null) {
            try {
                description = RepositoryDocument.description(description);
            } catch (IllegalArgumentException refused) {
                text(response, 400, refused.getMessage());
                return;
            }
        }
        RepositoryRouting.Route described = routing.route(servlet);
        if (format == null && description != null && !described.repository().isEmpty()) {
            if (!describe(described, key, description)) {
                text(response, 404, "There is no repository '" + described.repository() + "'.");
                return;
            }
            response.setStatus(200);
            return;
        }
        List<String> offered = RepositoryType.offerable();
        if (format == null || !offered.contains(format)) {
            text(response, 400, "'" + format + "' is not a format a repository can hold here; one of " + offered
                    + ".");
            return;
        }
        RepositoryRouting.Route route = described;
        if (route.repository().isEmpty()) {
            text(response, 400, "The request names no repository.");
            return;
        }
        if (RepositoryRemoval.removing(route.store())) {
            text(response, 409, "Repository '" + route.repository() + "' is still being deleted; create it again "
                    + "once it is gone.");
            return;
        }
        RepositoryType.Creation creation = RepositoryType.create(route.store(), format);
        if (creation != RepositoryType.Creation.CONFLICT && description != null) {
            describe(route, key, description);
        }
        switch (creation) {
            case CREATED -> {
                audit(route.tenant(), key, AuditActions.REPOSITORY_CREATE, route.repository());
                response.setStatus(201);
            }
            case RETYPED -> {
                audit(route.tenant(), key, AuditActions.REPOSITORY_RETYPE, route.repository() + " to " + format);
                response.setStatus(200);
            }
            case UNCHANGED -> response.setStatus(200);
            case CONFLICT -> text(response, 409, "Repository '" + route.repository() + "' already holds "
                    + RepositoryDocument.read(route.store()).map(RepositoryDocument::format).orElse("another format")
                    + ", which '" + format + "' does not hold everything of - what is stored there would stop "
                    + "answering.");
        }
    }

    /** Give the routed repository {@code description}; {@code false} when it has no document to describe. */
    private boolean describe(RepositoryRouting.Route route, String key, String description) throws IOException {
        if (!RepositoryDocument.describe(route.store(), description)) {
            return false;
        }
        RepositoryDocument.forget(repositories.root(), route.tenant(), route.repository());
        audit(route.tenant(), key, AuditActions.REPOSITORY_DESCRIBE, route.repository());
        return true;
    }

    /**
     * Delete a repository and everything it holds - {@code DELETE /repository/<tenant>/<name>} - and forget what it
     * was defined as, through the one removal every surface makes ({@link RepositoryRemoval}). The repository stops
     * answering before this returns; its objects are removed off the request path, so the answer is {@code 202}, and a
     * repository already being deleted - one a node stopped part way - is resumed. {@code 404} when there is none.
     *
     * <p>Forgetting the definition reads the settings document - one object per module under a constant prefix, the
     * read {@code DELETE /api/repositories/{name}} makes - and nothing on the request path reads the repository's
     * objects: the purge pages its scan on a thread of its own.
     */
    @DeleteMapping("/repository/{tenant}/{name}")
    public void deleteRepository(@PathVariable("name") String name,
                                 @RequestHeader(value = Repositories.KEY, required = false) String key,
                                 HttpServletRequest servlet, HttpServletResponse response) throws IOException {
        RepositoryRouting.Route route = routing.route(servlet);
        if (route.repository().isEmpty()) {
            text(response, 400, "The request names no repository.");
            return;
        }
        String repository = route.repository();
        RepositoryRemoval.Begun begun = RepositoryRemoval.begin(route.store());
        if (begun == RepositoryRemoval.Begun.ABSENT) {
            text(response, 404, "There is no repository '" + repository + "'.");
            return;
        }
        settings.set(SettingsScopes.repositoryKey(repository), null);
        RepositoryDocument.forget(repositories.root(), route.tenant(), repository);
        audit(route.tenant(), key, AuditActions.REPOSITORY_DELETE, repository);
        RepositoryRemoval.purgeInBackground(repositories.tenantScope(route.tenant()), repository,
                route.tenant() + "/" + repository);
        text(response, 202, begun == RepositoryRemoval.Begun.RESUMED
                ? "Resumed deleting repository '" + repository + "'."
                : "Deleting repository '" + repository + "'; it no longer answers, and everything it held is being "
                        + "removed.");
    }

    private static void text(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    @DeleteMapping("/api/repositories/{name}")
    public void removeRepositoryDefinition(@PathVariable("name") String name,
                                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                                           HttpServletResponse response) throws IOException {
        settings.set(SettingsScopes.repositoryKey(name), null);
        audit(key, AuditActions.REPOSITORY_REMOVE, name);
        response.setStatus(200);
    }

    /** The per-format proxy upstreams set at runtime ({@code format-upstream.<format>}): each language format with the
     *  upstream URL its local misses pull through, over the deployment's file-configured default. */
    @GetMapping("/api/upstreams")
    @ResponseBody
    public List<NamedValue> upstreams() {
        return stored(SettingsScopes.UPSTREAM_PREFIX);
    }

    @PutMapping("/api/upstreams/{format}")
    public void setUpstream(@PathVariable("format") String format,
                            @RequestHeader(value = Repositories.KEY, required = false) String key,
                            @RequestBody NamedValueRequest request,
                            HttpServletResponse response) throws IOException {
        if (request == null || request.value() == null || request.value().isBlank()) {
            response.setStatus(400);
            return;
        }
        URI upstream;
        try {
            upstream = URI.create(request.value());
        } catch (RuntimeException _) {
            response.setStatus(400);
            return;
        }
        // The same screen, on the other spelling of the same operator-configured target: format-upstream.<format>
        // is the live override of the jenreg.proxy.<format> boot default, and one repository
        // pulling in cleartext is the same hazard whichever key names it.
        String refused = RepositoryDefinition.upstreamRefusal(upstream, allowInternal());
        if (refused != null) {
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("The '" + format + "' upstream '" + request.value() + "' is refused: "
                    + refused + "." + RepositoryDefinition.upstreamRemedy());
            return;
        }
        settings.set(SettingsScopes.upstreamKey(format), request.value());
        audit(key, AuditActions.UPSTREAM_SET, format);
        response.setStatus(200);
    }

    @DeleteMapping("/api/upstreams/{format}")
    public void removeUpstream(@PathVariable("format") String format,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               HttpServletResponse response) throws IOException {
        settings.set(SettingsScopes.upstreamKey(format), null);
        audit(key, AuditActions.UPSTREAM_REMOVE, format);
        response.setStatus(200);
    }

    /** The stored settings whose key carries a prefix (a map entry), as name (prefix stripped) to value. */
    private List<NamedValue> stored(String prefix) {
        List<NamedValue> entries = new ArrayList<>();
        settings.overrides().forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                entries.add(new NamedValue(key.substring(prefix.length()), value));
            }
        });
        return entries;
    }

    /** The upstream hosts that carry a proxy credential, so a private registry can be proxied. Only the hosts are
     *  returned, never the credential: the secret is write-only, kept out of {@code config/settings}. */
    @GetMapping("/api/upstreams/auth")
    @ResponseBody
    public List<String> upstreamCredentialHosts(HttpServletResponse response) throws IOException {
        if (upstreamCredentials == UpstreamCredentialSource.NONE) {
            respondUpstreamAuthNotInstalled(response);
            return null;
        }
        return new ArrayList<>(upstreamCredentials.hosts());
    }

    /** With no upstream-credential module installed the credential endpoints answer 501, after the auth check so
     *  401/403 still precede. */
    private static void respondUpstreamAuthNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("upstream credentials are not installed on this deployment");
    }

    @PutMapping("/api/upstreams/auth/{host}")
    public void setUpstreamCredential(@PathVariable("host") String host,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestBody UpstreamAuthRequest request,
                                      HttpServletResponse response) throws IOException {
        if (upstreamCredentials == UpstreamCredentialSource.NONE) {
            respondUpstreamAuthNotInstalled(response);
            return;
        }
        Optional<UpstreamCredential> credential = request == null ? Optional.empty() : UpstreamCredential.of(
                request.scheme(), request.username(), request.password(), request.token(), request.header());
        if (credential.isEmpty()) {
            response.setStatus(400);
            return;
        }
        try {
            upstreamCredentials.set(host, credential.get());
        } catch (IllegalStateException refused) {
            // An upstream credential write with no master key configured is refused (§9), naming the remedy; the
            // credential is encrypted at rest like a SECRET setting, so nothing was persisted.
            refuseSecret(response, refused);
            return;
        }
        audit(key, AuditActions.UPSTREAM_AUTH_SET, host);
        response.setStatus(200);
    }

    @DeleteMapping("/api/upstreams/auth/{host}")
    public void removeUpstreamCredential(@PathVariable("host") String host,
                                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                                         HttpServletResponse response) throws IOException {
        if (upstreamCredentials == UpstreamCredentialSource.NONE) {
            respondUpstreamAuthNotInstalled(response);
            return;
        }
        upstreamCredentials.remove(host);
        audit(key, AuditActions.UPSTREAM_AUTH_REMOVE, host);
        response.setStatus(200);
    }


    /** The catalogue of runtime-editable settings - the single source for what the API, console and CLI may change
     *  (the maps - repository definitions and format upstreams - have their own CRUD). The neutral core dogfoods the
     *  same {@code SettingsContributor} SPI its plugin modules use ({@code CoreSettingsContributor}), so this collapses
     *  to {@link SettingsContributor#all()} - the core is described once, not inlined here and again in the console's
     *  {@code SettingsAdmin}, and the catalogue always matches the modules on this deployment. */
    private List<Setting> catalogue() {
        return catalogue;
    }

    /** Refuse a SECRET write the deployment cannot encrypt at rest (no master key configured): {@code 400} with the
     *  §9 remedy from {@link Settings}, which names {@code JENREG_SECRETS_KEY}. Nothing was persisted. */
    private static void refuseSecret(HttpServletResponse response, IllegalStateException refused) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(refused.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) throws IOException {
        response.setStatus(400);
    }

    /** One runtime setting for the API: its key, its {@link Setting.Kind kind} (so a client masks a SECRET and picks
     *  the right control), its effective value and its file/env default, whether a stored override is in force,
     *  whether a change applies live, and whether it is pinned from above the store (with the phrase naming the pin).
     *  A SECRET's {@code value} and {@code defaultValue} are always {@code null} - the value is write-only and never
     *  read back - while {@code overridden}/{@code pinned} still say whether it is set. */
    public record SettingView(String key, String kind, String value, String defaultValue, boolean overridden,
                              boolean appliesImmediately, boolean pinned, String pinnedBy,
                              String group, String label, String description) {
    }

    /** The first-run setup guide as the API serves it: the steps, each with the rows it is about. */
    public record SetupView(List<StepView> steps) {
    }

    /** One step of the guide: its id, title and the sentence on why it is asked, and the settings rows this
     *  deployment carries for it - the same rows {@code GET /api/settings} lists, documentation included. */
    public record StepView(String id, String title, String why, List<SettingView> settings) {
    }

    public record SettingRequest(String value) {
    }

    public record NamedValue(String name, String value) {
    }

    public record NamedValueRequest(String value) {
    }

    /** A repository's creation: the format it holds, and an optional description. */
    public record RepositoryRequest(String value, String description) {
    }

    public record UpstreamAuthRequest(String scheme, String username, String password, String token, String header) {
    }
}
