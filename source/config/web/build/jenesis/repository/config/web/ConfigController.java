package build.jenesis.repository.config.web;

import module java.base;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.Wizard;
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
 * The deployment-config management surface - the runtime settings catalogue, the runtime repository definitions, the
 * per-format proxy upstreams and the per-host upstream credentials - contributed through the
 * {@code ServerModuleProvider} seam. It reads the store-backed {@link Settings}, changes them only through the
 * {@link SettingsEditor} the console uses too, and manages the discovered {@link UpstreamCredentialSource}; a mutation
 * is audited under the tenant the routing answers for the request ({@link RepositoryRouting#tenant}).
 *
 * <p>These are deployment-wide knobs: the security chain gates every {@code /api/} route {@code manage:write} or
 * {@code manage:read} at scope {@code *} - operator tenant only - before it is reached, so this controller makes no
 * authorization decision. Without an upstream-credential module {@code /api/upstreams/auth} answers {@code 501}, after
 * the auth check.
 */
@RestController
public class ConfigController {

    /** The header a setting write answers with: {@code now} when the change applies at once, {@code restart} when the
     *  setting is read as the node starts - a feed switch, a store backend - so the write waits for the next start. */
    public static final String APPLIES_ON = "Jenesis-Applies-On";


    private final Repositories repositories;
    /** The one place a setting is changed; its settings are what the reads render. */
    private final SettingsEditor editor;
    private final Settings settings;
    private final UpstreamCredentialSource upstreamCredentials;
    private final AuditTrail audit;
    private final RepositoryRouting routing;
    /** Whether a presented key is the deployment operator's - an operator-only setting, such as a repository's routing,
     *  needs one. */
    private final Predicate<String> operator;

    public ConfigController(Repositories repositories, SettingsEditor editor,
                            UpstreamCredentialSource upstreamCredentials,
                            AuditTrail audit, RepositoryRouting routing, Predicate<String> operator) {
        this.operator = operator;
        this.editor = editor;
        this.settings = editor.settings();
        this.repositories = repositories;
        this.upstreamCredentials = upstreamCredentials;
        this.audit = audit;
        this.routing = routing;
    }

    private void audit(String tenant, String key, String action, String target) {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, target);
    }

    /** The deployment-wide runtime settings: each editable key with its effective value, its file or environment
     *  default, whether a stored override is in force, and whether a source above the store pins it (with the phrase
     *  naming it) - a pinned key ignores the store, so a client greys it and a write is refused. Settings that cannot
     *  change at runtime (storage backend, listen port, auth enforcement) are not listed. With {@code ?tenant=}, that
     *  tenant's settings with the deployment's value as baseline. */
    @GetMapping("/api/settings")
    @ResponseBody
    public List<SettingView> settings(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestParam(value = "tenant", required = false) String tenant) {
        return tenant != null && !tenant.isBlank() ? rows(Setting.Scope.TENANT, tenant)
                : rows(Setting.Scope.GLOBAL, null);
    }

    /** A level's settings as the settings editor reads them for every surface ({@link SettingsEditor#rows}). */
    private List<SettingView> rows(Setting.Scope level, String tenant) {
        List<SettingView> view = new ArrayList<>();
        try {
            for (SettingsEditor.Row row : editor.rows(level, tenant, null)) {
                view.add(view(row.setting(), row.effective(), row.inherited(), row.overridden(), row.pin()));
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return view;
    }

    /** The first boot's wizard, the one {@code /ui/setup} runs and the CLI's {@code setup} prints: the starter
     *  credential's step, then one step per group of essential deployment and tenant settings ({@link Wizard#SETUP})
     *  with its rows, so the wizard is a capability on all three surfaces. Writes go through
     *  {@code PUT /api/settings/<key>}. Its one store read is the settings document - one object per module under a
     *  constant prefix. */
    @GetMapping("/api/setup")
    @ResponseBody
    public SetupView setup(@RequestHeader(value = Repositories.KEY, required = false) String key) {
        Map<String, SettingView> rows = new LinkedHashMap<>();
        for (SettingView row : settings(key, null)) {
            rows.put(row.key(), row);
        }
        List<StepView> steps = new ArrayList<>();
        for (Wizard.Information information : Wizard.SETUP.information()) {
            steps.add(new StepView(information.id(), information.title(), information.text(), List.of()));
        }
        for (Wizard.Step step : Wizard.SETUP.steps()) {
            List<SettingView> asked = step.settings().stream().map(setting -> rows.get(setting.key()))
                    .filter(Objects::nonNull).toList();
            if (!asked.isEmpty()) {
                steps.add(new StepView(step.group().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"),
                        step.group(), "", asked));
            }
        }
        return new SetupView(steps);
    }

    /** One setting's API view with its {@link Setting.Kind kind}. A SECRET value is never emitted - null on read, so
     *  neither the stored value nor a tenant view's baseline reaches the JSON - while {@code overridden}/{@code pinned}
     *  say whether it is set; a client changes it by writing a new value. */
    private static SettingView view(Setting setting, String effective, String baseline, boolean overridden,
                                    Optional<PinnedSettings.Pin> pin) {
        boolean secret = setting.kind() == Setting.Kind.SECRET;
        return new SettingView(setting.key(), setting.kind().name(),
                secret ? null : effective, secret ? null : baseline, overridden, setting.live(),
                pin.isPresent(), pin.map(PinnedSettings.Pin::source).orElse(""),
                setting.group(), setting.label(), setting.description(), setting.tier() == Setting.Tier.ADVANCED,
                setting.tier() == null ? "" : setting.tier().name());
    }

    /** Set a runtime override for one setting, deployment-wide or for a {@code tenant}, through the
     *  {@link SettingsEditor}: {@code 400} naming the refusal when the catalogue refuses the value or the deployment
     *  would not resolve with it, {@code 409} when an operator pinned the key above the store. A live setting applies
     *  on this node at once, the rest on restart, and {@value #APPLIES_ON} says which ({@code now} or
     *  {@code restart}). */
    @PutMapping("/api/settings/{key}")
    public void setSetting(@PathVariable("key") String key,
                           @RequestHeader(value = Repositories.KEY, required = false) String authKey,
                           @RequestParam(value = "tenant", required = false) String tenant,
                           @RequestBody SettingRequest request, HttpServletRequest http,
                           HttpServletResponse response) throws IOException {
        String value = request == null || request.value() == null ? "" : request.value();
        changeSetting(key, value, authKey, tenant, http, response);
    }

    private void changeSetting(String key, String value, String authKey, String tenant, HttpServletRequest http,
                               HttpServletResponse response) throws IOException {
        Map<String, String> values = one(key, value);
        change(http, authKey, response, actor -> {
            if (tenant != null && !tenant.isBlank()) {
                editor.tenant(tenant, values, true, actor);
            } else {
                editor.deployment(values, actor);
            }
        });
        if (response.getStatus() == 200) {
            SettingsScopes.declared(key).ifPresent(setting ->
                    response.setHeader(APPLIES_ON, setting.live() ? "now" : "restart"));
        }
    }

    /** Clear a runtime override, reverting to the file or environment default (or, for a {@code tenant}, the
     *  deployment's value), refused as its {@link #setSetting PUT twin} is. */
    @DeleteMapping("/api/settings/{key}")
    public void clearSetting(@PathVariable("key") String key,
                             @RequestHeader(value = Repositories.KEY, required = false) String authKey,
                             @RequestParam(value = "tenant", required = false) String tenant,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        changeSetting(key, "", authKey, tenant, request, response);
    }

    /** Dump the stored settings as one JSON bundle for backup or transfer. Without a {@code tenant}: the deployment's
     *  documents keyed by module plus every tenant's slice keyed {@code tenant:<tenant>:<module>}; with one, that
     *  tenant's documents. Credential-free by construction: {@link Settings#exportBundle} and
     *  {@link Settings#documents(String)} exclude every SECRET key through {@code SettingsSecrets}, and upstream
     *  credentials are never in {@code config/settings}. */
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

    /** Restore a bundle {@link #exportSettings} produced: without a {@code tenant} the deployment's documents and every
     *  tenant slice, with one that slice ({@link SettingsEditor#importBundle}, {@link SettingsEditor#importTenant}). A
     *  bundle that would not resolve or carries a refused value is {@code 400}; one setting a pinned key is
     *  {@code 409}, naming every such key. Nothing is written unless all of it is accepted. */
    @PostMapping("/api/settings/import")
    public void importSettings(@RequestHeader(value = Repositories.KEY, required = false) String authKey,
                               @RequestParam(value = "tenant", required = false) String tenant,
                               @RequestBody(required = false) Map<String, Map<String, String>> bundle,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (bundle == null) {
            response.setStatus(400);
            return;
        }
        change(request, authKey, response, actor -> {
            if (tenant == null || tenant.isBlank()) {
                editor.importBundle(bundle, actor);
            } else {
                editor.importTenant(tenant, bundle, actor);
            }
        });
    }

    /** A repository's settings - every repository setting in the catalogue with its effective value, what it would
     *  inherit ({@code defaultValue}) and whether it sets its own ({@link SettingsEditor#rows}), in the tenant the
     *  routing answers. It reads the repository's settings documents and the cached tenant and deployment snapshots,
     *  nothing that grows with its content. */
    @GetMapping("/api/repository/settings")
    @ResponseBody
    public List<SettingView> repositorySettings(@RequestParam("repo") String repo, HttpServletRequest http)
            throws IOException {
        List<SettingView> view = new ArrayList<>();
        for (SettingsEditor.Row row : editor.rows(Setting.Scope.REPOSITORY, repositoryTenant(repo, http), repo)) {
            view.add(view(row.setting(), row.effective(), row.inherited(), row.overridden(), row.pin()));
        }
        return view;
    }

    /** Set one repository setting through the catalogue: {@code 400} naming the refusal - an unknown key, a refused
     *  value, a pinned key, an operator-only one without the operator's key - with nothing stored. */
    @PutMapping("/api/repository/settings/{key}")
    public void setRepositorySetting(@PathVariable("key") String name, @RequestParam("repo") String repo,
                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                     @RequestBody SettingRequest request, HttpServletRequest http,
                                     HttpServletResponse response) throws IOException {
        String value = request == null || request.value() == null ? "" : request.value();
        writeRepositorySetting(repo, name, value, key, http, response);
    }

    /** Clear one repository setting, so the repository inherits its tenant's and the deployment's again. */
    @DeleteMapping("/api/repository/settings/{key}")
    public void clearRepositorySetting(@PathVariable("key") String name, @RequestParam("repo") String repo,
                                       @RequestHeader(value = Repositories.KEY, required = false) String key,
                                       HttpServletRequest http, HttpServletResponse response) throws IOException {
        writeRepositorySetting(repo, name, "", key, http, response);
    }

    private void writeRepositorySetting(String repo, String name, String value, String key, HttpServletRequest http,
                                        HttpServletResponse response) throws IOException {
        String tenant = repositoryTenant(repo, http);
        change(http, key, response, actor -> editor.repository(tenant, repo, one(name, value), operator.test(key),
                actor));
    }

    /** The tenant a repository operation answers for, once its repository name is a routable one. */
    private String repositoryTenant(String repo, HttpServletRequest http) {
        if (!Repositories.valid(repo)) {
            throw new IllegalArgumentException("Not a routable repository name: " + repo);
        }
        return routing.tenant(http);
    }

    /** The repositories defined at runtime ({@code repositories.<name>}): each name with its routing (one or more
     *  {@code writable} / {@code fallback <source> [options]} clauses), adding to or overriding the deployment's
     *  {@code jenrepo.repositories.<name>} and routing on the next request. With {@code ?tenant=}, that tenant's own.
     *  It reads settings documents only. */
    @GetMapping("/api/repositories")
    @ResponseBody
    public List<NamedValue> repositoryDefinitions(@RequestParam(value = "tenant", required = false) String tenant) {
        if (tenant != null) {
            throw new IllegalArgumentException(TENANT_ROUTING);
        }
        return stored(null, SettingsScopes.REPOSITORY_PREFIX);
    }

    /** Why a tenant has no repository definitions of its own. */
    private static final String TENANT_ROUTING = "A tenant has no repository definitions: each repository is routed by "
            + "its own routing setting, PUT /api/repository/settings/routing?repo=<name>.";

    /** Define a repository name deployment-wide ({@link SettingsEditor#definition}), parsed as the boot sweep parses it
     *  and its upstreams screened, so a definition that would not route, or would fetch from where it must not, is a
     *  {@code 400} naming why and the fix. */
    @PutMapping("/api/repositories/{name}")
    public void setRepositoryDefinition(@PathVariable("name") String name,
                                        @RequestHeader(value = Repositories.KEY, required = false) String key,
                                        @RequestParam(value = "tenant", required = false) String tenant,
                                        @RequestBody NamedValueRequest request,
                                        HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (tenant != null) {
            text(response, 400, TENANT_ROUTING);
            return;
        }
        if (!Repositories.valid(name) || request == null || request.value() == null || request.value().isBlank()) {
            response.setStatus(400);
            return;
        }
        change(http, key, response, actor -> editor.definition(name, request.value(), actor));
    }

    /**
     * Create a repository holding one format - {@code PUT /repository/<tenant>/<name>} with
     * {@code {"value":"<format>"}} - in the tenant the routing decides, through the creation every surface makes
     * ({@link RepositoryType#create}). A repository with content but no format gets this one, and one whose type the
     * requested one covers ({@code maven} asked to be {@code java}) gets the requested one. {@code 201} when created,
     * {@code 200} when it already held or was given the type, {@code 409} when it holds a type the requested one does
     * not cover or is being deleted, {@code 400} for a type no repository can hold.
     *
     * <p>A {@code "description"} beside the format sets it (empty clears it); a description alone describes an existing
     * repository - {@code 200}, or {@code 404} when there is none.
     *
     * <p>A {@code "settings"} map creates the repository with its own settings in one step: every value is validated
     * first, a refusal is {@code 400} naming every refused value with nothing written, and the settings are stored
     * before the document that makes the repository exist
     * ({@link RepositoryType#create(ArtifactStore, String, String, RepositoryType.Configuration)}), so its first
     * request is answered as configured. Such a creation only creates: an existing repository is {@code 409} and left
     * as it was.
     *
     * <p>The routing is asked before the body is read, so a request naming a tenant it may not address is refused
     * whatever it carries; an empty body is then judged as naming no format.
     */
    @PutMapping("/repository/{tenant}/{name}")
    public void createRepository(@PathVariable("name") String name,
                                 @RequestHeader(value = Repositories.KEY, required = false) String key,
                                 @RequestBody(required = false) RepositoryRequest request,
                                 HttpServletRequest servlet, HttpServletResponse response) throws IOException {
        RepositoryRouting.Route described = routing.route(servlet);
        String format = request == null ? null : request.value();
        String description = request == null ? null : request.description();
        Map<String, String> configured = request == null || request.settings() == null ? Map.of()
                : request.settings();
        if (description != null) {
            try {
                description = RepositoryDocument.description(description);
            } catch (IllegalArgumentException refused) {
                text(response, 400, refused.getMessage());
                return;
            }
        }
        if (format == null && !configured.isEmpty()) {
            text(response, 400, "Settings are given when a repository is created, beside its format; an existing "
                    + "repository's are set through PUT /api/repository/settings/<key>?repo=<name>.");
            return;
        }
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
        boolean operatorKey = operator.test(key);
        if (!configured.isEmpty()) {
            SortedMap<String, String> refused = editor.refusals(Setting.Scope.REPOSITORY, configured, operatorKey);
            if (!refused.isEmpty()) {
                text(response, 400, "Repository '" + route.repository() + "' was not created: "
                        + String.join(" ", refused.values()));
                return;
            }
        }
        String tenant = route.tenant();
        String repository = route.repository();
        RepositoryType.Creation creation = RepositoryType.create(route.store(), format, description,
                configured.isEmpty() ? null
                        : () -> editor.repository(tenant, repository, configured, operatorKey, actor(servlet, key)));
        if ((creation == RepositoryType.Creation.UNCHANGED || creation == RepositoryType.Creation.RETYPED)
                && description != null) {
            describe(route, key, description);
        }
        switch (creation) {
            case CREATED -> {
                audit(tenant, key, AuditActions.REPOSITORY_CREATE, repository);
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
            case EXISTS -> text(response, 409, "Repository '" + repository + "' already exists; a creation that "
                    + "carries settings creates a repository and changes none. Set an existing repository's through "
                    + "PUT /api/repository/settings/<key>?repo=" + repository + ".");
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

    /** Delete a repository and everything it holds, its own settings included -
     *  {@code DELETE /repository/<tenant>/<name>} - through the removal every surface makes
     *  ({@link RepositoryRemoval}). The deployment's definition of the name is every tenant's, so it stays. The
     *  repository stops answering before this returns and its objects go off the request path, so the answer is
     *  {@code 202}, and {@link #repositoryDeletion} says where it stands; a deletion a node stopped part way is
     *  resumed. {@code 404} when there is none. */
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
        // The repository's own settings go with its objects; the deployment's definition of its name stays.
        RepositoryDocument.forget(repositories.root(), route.tenant(), repository);
        audit(route.tenant(), key, AuditActions.REPOSITORY_DELETE, repository);
        RepositoryRemoval.purgeInBackground(repositories.tenantScope(route.tenant()), repository,
                route.tenant() + "/" + repository);
        text(response, 202, begun == RepositoryRemoval.Begun.RESUMED
                ? "Resumed deleting repository '" + repository + "'."
                : "Deleting repository '" + repository + "'; it no longer answers, and everything it held is being "
                        + "removed. GET /api/repository/deletion?repo=" + repository + " says when it is gone.");
    }

    /** Where the deletion of a repository stands - {@code GET /api/repository/deletion?repo=} - read from its removal
     *  marker and its document, two point reads ({@link RepositoryRemoval#status}): {@code running}, {@code failed}
     *  with the reason its purge stopped, {@code gone} once neither the repository nor its deletion is left, or
     *  {@code present} when nothing is deleting it. What a caller that started a deletion polls. */
    @GetMapping("/api/repository/deletion")
    @ResponseBody
    public Deletion repositoryDeletion(@RequestParam("repo") String repo, HttpServletRequest http)
            throws IOException {
        RepositoryRemoval.Status status = RepositoryRemoval.status(
                repositories.store(repositoryTenant(repo, http), repo));
        return new Deletion(repo, status.state().name().toLowerCase(Locale.ROOT), status.startedAt(),
                status.failure());
    }

    /** A repository's deletion as {@code GET /api/repository/deletion} answers it. */
    public record Deletion(String repository, String state, Instant startedAt, String failure) {
    }

    private static void text(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    @DeleteMapping("/api/repositories/{name}")
    public void removeRepositoryDefinition(@PathVariable("name") String name,
                                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                                           @RequestParam(value = "tenant", required = false) String tenant,
                                           HttpServletRequest request,
                                           HttpServletResponse response) throws IOException {
        if (tenant != null) {
            text(response, 400, TENANT_ROUTING);
            return;
        }
        change(request, key, response, actor -> editor.definition(name, null, actor));
    }

    /** The per-format proxy upstreams set at runtime ({@code format-upstream.<format>}): each format with the upstream
     *  its misses pull through, over the file-configured default. With {@code ?tenant=}, that tenant's own. Settings
     *  documents only. */
    @GetMapping("/api/upstreams")
    @ResponseBody
    public List<NamedValue> upstreams(@RequestParam(value = "tenant", required = false) String tenant) {
        return stored(tenant, SettingsScopes.UPSTREAM_PREFIX);
    }

    /** Name a format's upstream - the deployment's, or with {@code ?tenant=} that tenant's
     *  ({@link SettingsEditor#upstream}) - screened as every outbound target is. */
    @PutMapping("/api/upstreams/{format}")
    public void setUpstream(@PathVariable("format") String format,
                            @RequestHeader(value = Repositories.KEY, required = false) String key,
                            @RequestParam(value = "tenant", required = false) String tenant,
                            @RequestBody NamedValueRequest request,
                            HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (request == null || request.value() == null || request.value().isBlank() || !tenantName(tenant)) {
            response.setStatus(400);
            return;
        }
        change(http, key, response, actor -> editor.upstream(tenant, format, request.value(), actor));
    }

    @DeleteMapping("/api/upstreams/{format}")
    public void removeUpstream(@PathVariable("format") String format,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               @RequestParam(value = "tenant", required = false) String tenant,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!tenantName(tenant)) {
            response.setStatus(400);
            return;
        }
        change(request, key, response, actor -> editor.upstream(tenant, format, null, actor));
    }

    /** Whether a routing write's {@code tenant} is absent (the deployment's) or a name a tenant can have. */
    private static boolean tenantName(String tenant) {
        return tenant == null || tenant.isBlank() || SettingsDocuments.validTenant(tenant);
    }

    /** The stored settings whose key carries {@code prefix}, as name (prefix stripped) to value - the deployment's, or
     *  a tenant's own. */
    private List<NamedValue> stored(String tenant, String prefix) {
        List<NamedValue> entries = new ArrayList<>();
        if (!tenantName(tenant)) {
            return entries;
        }
        Map<String, String> overrides = tenant == null || tenant.isBlank()
                ? settings.overrides() : settings.overrides(tenant);
        overrides.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                entries.add(new NamedValue(key.substring(prefix.length()), value));
            }
        });
        return entries;
    }

    /** The upstream hosts carrying a proxy credential, so a private registry can be proxied. Only hosts are returned:
     *  the secret is write-only and kept out of {@code config/settings}. */
    @GetMapping("/api/upstreams/auth")
    @ResponseBody
    public List<String> upstreamCredentialHosts(HttpServletResponse response) throws IOException {
        if (upstreamCredentials == UpstreamCredentialSource.NONE) {
            respondUpstreamAuthNotInstalled(response);
            return null;
        }
        return new ArrayList<>(upstreamCredentials.hosts());
    }

    /** Without an upstream-credential module the credential endpoints answer 501, after the auth check. */
    private static void respondUpstreamAuthNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("upstream credentials are not installed on this deployment");
    }

    @PutMapping("/api/upstreams/auth/{host}")
    public void setUpstreamCredential(@PathVariable("host") String host,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestBody UpstreamAuthRequest request,
                                      HttpServletRequest http, HttpServletResponse response) throws IOException {
        String routed = routing.tenant(http);
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
            // No master key is configured, and the credential is encrypted at rest like a SECRET setting: refused with
            // the remedy, nothing persisted.
            refuseSecret(response, refused);
            return;
        }
        audit(routed, key, AuditActions.UPSTREAM_AUTH_SET, host);
        response.setStatus(200);
    }

    @DeleteMapping("/api/upstreams/auth/{host}")
    public void removeUpstreamCredential(@PathVariable("host") String host,
                                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        String routed = routing.tenant(request);
        if (upstreamCredentials == UpstreamCredentialSource.NONE) {
            respondUpstreamAuthNotInstalled(response);
            return;
        }
        upstreamCredentials.remove(host);
        audit(routed, key, AuditActions.UPSTREAM_AUTH_REMOVE, host);
        response.setStatus(200);
    }


    /** Refuse a SECRET write the deployment cannot encrypt at rest: {@code 400} with the remedy, which names
     *  {@code JENREPO_SECRETS_KEY}. Nothing is persisted. */
    private static void refuseSecret(HttpServletResponse response, IllegalStateException refused) throws IOException {
        text(response, 400, refused.getMessage());
    }

    /** A change, made as {@code actor}. */
    @FunctionalInterface
    private interface Change {
        void apply(SettingsEditor.Actor actor) throws IOException;
    }

    /** Make a change through the settings editor, answering {@code 200}; a refusal is {@code 409} when what it sets is
     *  pinned above the store and {@code 400} otherwise - a refused value, a deployment that would not resolve, a
     *  secret that cannot be sealed - with the editor's sentence, and nothing written. */
    private void change(HttpServletRequest http, String key, HttpServletResponse response, Change change)
            throws IOException {
        try {
            change.apply(actor(http, key));
        } catch (IllegalArgumentException | IllegalStateException refused) {
            text(response, refused instanceof SettingsEditor.Pinned ? 409 : 400, refused.getMessage());
            return;
        }
        response.setStatus(200);
    }

    /** Who a request acts as on the audit trail: the tenant the routing answers for it, and its key's hash. */
    private SettingsEditor.Actor actor(HttpServletRequest http, String key) {
        return new SettingsEditor.Actor(routing.tenant(http), key == null ? "anonymous" : Authorization.hash(key));
    }

    /** One value, blank for a clear. */
    private static Map<String, String> one(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value == null ? "" : value);
        return values;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) throws IOException {
        response.setStatus(400);
    }

    /** One runtime setting for the API: its key and {@link Setting.Kind kind} (so a client masks a SECRET and picks a
     *  control), its effective value and file/env default, whether an override is in force, whether a change applies
     *  live, whether and by what it is pinned, and its {@link Setting.Tier tier}. A SECRET's {@code value} and
     *  {@code defaultValue} are always {@code null}; {@code overridden}/{@code pinned} still say whether it is set. */
    public record SettingView(String key, String kind, String value, String defaultValue, boolean overridden,
                              boolean appliesImmediately, boolean pinned, String pinnedBy,
                              String group, String label, String description, boolean advanced, String tier) {
    }

    /** The first-run setup guide as the API serves it: the steps, each with the rows it is about. */
    public record SetupView(List<StepView> steps) {
    }

    /** One wizard step: its id, title and text (the starter credential's step asks no setting) and the settings rows it
     *  asks, the rows {@code GET /api/settings} lists. */
    public record StepView(String id, String title, String why, List<SettingView> settings) {
    }

    public record SettingRequest(String value) {
    }

    public record NamedValue(String name, String value) {
    }

    public record NamedValueRequest(String value) {
    }

    /** A repository's creation: the format it holds, an optional description, and optionally the settings it is
     *  created with. */
    public record RepositoryRequest(String value, String description, Map<String, String> settings) {
    }

    public record UpstreamAuthRequest(String scheme, String username, String password, String token, String header) {
    }
}
