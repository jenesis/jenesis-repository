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
 * The deployment-config management surface - the runtime-editable settings catalogue, the runtime repository
 * definitions, the per-format proxy upstreams and the per-host upstream credentials - peeled out of the
 * {@code RepositoryController} monolith into its own thin {@code web} adapter and contributed through the
 * {@code ServerModuleProvider} seam. It reads the store-backed {@link Settings} and changes them only through the
 * one settings editor ({@link SettingsEditor}) the console calls too, and manages the discovered
 * {@link UpstreamCredentialSource}; a mutation is audited under the tenant the deployment's routing answers for the
 * request ({@link RepositoryRouting#tenant}).
 * These are deployment-wide knobs, so every route here is under {@code /api/} and is gated {@code manage:write} (the
 * mutations) or {@code manage:read} (the reads) at scope {@code *} by the security chain before the request is
 * reached - operator-tenant-only - so this controller makes no authorization decision, the same guard the monolith
 * carried, unchanged by the move. With no upstream-credential module installed the {@code /api/upstreams/auth}
 * endpoints answer {@code 501}, after the auth check so {@code 401}/{@code 403} still precede. A privileged mutation
 * writes an audit event.
 */
@RestController
public class ConfigController {

    /** The header a setting write answers with: {@code now} when the change applies at once, {@code restart} when the
     *  setting is read as the node starts - a feed switch, a store backend - so the write waits for the next start. */
    public static final String APPLIES_ON = "Jenesis-Applies-On";


    private final Repositories repositories;
    /** The one place a setting is changed, which every write here goes through; its settings are what the reads
     *  here render. */
    private final SettingsEditor editor;
    private final Settings settings;
    private final UpstreamCredentialSource upstreamCredentials;
    private final AuditTrail audit;
    private final RepositoryRouting routing;
    /** Whether a presented key is the deployment operator's - an operator-only setting, a repository's routing, is set
     *  only with one. */
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

    /** The deployment-wide runtime settings: each editable key with its effective value, its file/env default,
     *  whether a stored override is in force, and whether it is pinned from a source above the store (with the phrase
     *  naming what pins it) - a pinned key ignores the store, so a client greys the knob and a write is refused. The
     *  settings that cannot change at runtime (storage backend, listen port, whether auth is enforced) are not
     *  listed. With {@code ?tenant=}, that tenant's settings - the ones a tenant may hold, each with the deployment's
     *  value as its baseline. */
    @GetMapping("/api/settings")
    @ResponseBody
    public List<SettingView> settings(@RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestParam(value = "tenant", required = false) String tenant) {
        return tenant != null && !tenant.isBlank() ? rows(Setting.Scope.TENANT, tenant)
                : rows(Setting.Scope.GLOBAL, null);
    }

    /** A level's settings as the settings editor reads them for every surface ({@link SettingsEditor#rows}): the
     *  deployment's, or a tenant's - each row's effective value, what it inherits, whether the level set its own, and
     *  what pins it. */
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

    /**
     * The first boot's wizard - the one the console runs at {@code /ui/setup} and the CLI's {@code setup} verb prints:
     * the starter credential's step, then one step per group of the deployment's and a tenant's essential settings
     * ({@link Wizard#SETUP}), each with the settings rows it asks, so what the wizard asks is a capability on all three
     * surfaces and not a screen. Writes go through {@code PUT /api/settings/<key>} like any other. Its one store read
     * is the settings document {@code GET /api/settings} reads - one object per module under a constant prefix,
     * narrow by construction.
     */
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
                setting.group(), setting.label(), setting.description(), setting.tier() == Setting.Tier.ADVANCED,
                setting.tier() == null ? "" : setting.tier().name());
    }

    /** Set a runtime override for one editable setting, deployment-wide or - with a {@code tenant} - for that tenant,
     *  through the one settings editor ({@link SettingsEditor}): {@code 400} naming the refusal when the catalogue
     *  refuses the value or the deployment would not resolve with it, {@code 409} when an operator pinned the key
     *  above the store. A live setting takes effect on this node at once; the rest apply on restart, and the answer
     *  says which in {@value #APPLIES_ON} ({@code now} or {@code restart}) so a script is told as the console is. */
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

    /** Clear a runtime override, reverting the setting to its file/env default (or, with a {@code tenant}, to the
     *  deployment-wide value the tenant was overriding), refused as its {@link #setSetting PUT twin} is. */
    @DeleteMapping("/api/settings/{key}")
    public void clearSetting(@PathVariable("key") String key,
                             @RequestHeader(value = Repositories.KEY, required = false) String authKey,
                             @RequestParam(value = "tenant", required = false) String tenant,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        changeSetting(key, "", authKey, tenant, request, response);
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

    /** Restore a settings bundle produced by {@link #exportSettings}, parsed with the framework's JSON reader: with
     *  no {@code tenant} a full restore of the deployment's documents and every tenant slice, with a {@code tenant}
     *  that tenant's slice alone ({@link SettingsEditor#importBundle}, {@link SettingsEditor#importTenant}). A bundle
     *  that would not resolve, or carries a value its setting refuses, is {@code 400}; one that sets a pinned key is
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

    /**
     * A repository's settings - every repository setting the catalogue carries, with the repository's effective value,
     * what it would inherit from its tenant and the deployment ({@code defaultValue}), and whether it set its own - as
     * the settings editor reads them for every surface ({@link SettingsEditor#rows}). The tenant is the one the
     * deployment's routing answers for the request. Its reads are the repository's settings documents by name and the
     * cached tenant and deployment snapshots, nothing that grows with what the repository holds.
     */
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

    /** Set one repository setting, validated through the catalogue: {@code 400} naming the refusal - an unknown key,
     *  a value its kind or its module refuses, a pinned key, or an operator-only one without the operator's key - and
     *  nothing stored. */
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

    /** The repositories defined at runtime ({@code repositories.<name>} in the settings store): each name with its
     *  routing specification (one or more {@code writable} / {@code fallback <source> [options]} clauses). They add to
     *  or override the deployment's file-configured repositories ({@code jenrepo.repositories.<name>}) and
     *  route on the next request. With {@code ?tenant=}, the ones that tenant set for itself, which route its
     *  repositories over the deployment's. Either way it reads settings documents - one object per module under a
     *  constant prefix - and nothing that grows with what the repositories hold. */
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

    /** Define a repository name deployment-wide ({@link SettingsEditor#definition}): parsed as the boot sweep parses
     *  it and its upstreams screened, so a definition that would not route, or would fetch from where this deployment
     *  must not, is a {@code 400} naming why and the fix. */
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
     *
     * <p>A {@code "settings"} map beside the format creates the repository with those as its own settings, in one
     * step: every value is validated through the catalogue first, as {@code PUT /api/repository/settings/<key>} would
     * validate it, and a refusal answers {@code 400} naming every refused value with nothing written; the settings are
     * then stored before the document that makes the repository exist ({@link RepositoryType#create(ArtifactStore,
     * String, String, RepositoryType.Configuration)}), so it answers its first request as configured. Such a creation
     * only creates: a repository that exists already is answered {@code 409} and left as it was.
     *
     * <p>The routing is asked first, before the body is read or judged: a request naming a tenant it may not address
     * is refused as every surface refuses a caller without access, whatever it carries. The body is therefore not
     * required by the binding - an empty one is judged here, after the refusal, as naming no format.
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

    /**
     * Delete a repository and everything it holds, its own settings included - {@code DELETE
     * /repository/<tenant>/<name>} - through the one removal every surface makes ({@link RepositoryRemoval}); the
     * deployment's definition of its name is every tenant's, so it stays. The repository stops
     * answering before this returns; its objects are removed off the request path, so the answer is {@code 202}, and a
     * repository already being deleted - one a node stopped part way - is resumed. {@code 404} when there is none.
     *
     * <p>Nothing on the request path reads the repository's objects: the purge pages its scan on a thread of its
     * own.
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
        // The repository's own settings go with its objects; the deployment's definition of its name stays.
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
                                           @RequestParam(value = "tenant", required = false) String tenant,
                                           HttpServletRequest request,
                                           HttpServletResponse response) throws IOException {
        if (tenant != null) {
            text(response, 400, TENANT_ROUTING);
            return;
        }
        change(request, key, response, actor -> editor.definition(name, null, actor));
    }

    /** The per-format proxy upstreams set at runtime ({@code format-upstream.<format>}): each language format with the
     *  upstream URL its local misses pull through, over the deployment's file-configured default. With
     *  {@code ?tenant=}, the ones that tenant set for itself, which its repositories pull through instead. It reads
     *  settings documents - one object per module under a constant prefix - as the list above does. */
    @GetMapping("/api/upstreams")
    @ResponseBody
    public List<NamedValue> upstreams(@RequestParam(value = "tenant", required = false) String tenant) {
        return stored(tenant, SettingsScopes.UPSTREAM_PREFIX);
    }

    /** Name a format's upstream - the deployment's, or with {@code ?tenant=} that tenant's own
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

    /** The stored settings whose key carries a prefix (a map entry), as name (prefix stripped) to value: the
     *  deployment's, or with a tenant the ones that tenant set for itself. */
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
            // An upstream credential write with no master key configured is refused, naming the remedy; the
            // credential is encrypted at rest like a SECRET setting, so nothing was persisted.
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


    /** Refuse a SECRET write the deployment cannot encrypt at rest (no master key configured): {@code 400} with the
     *  remedy, which names {@code JENREPO_SECRETS_KEY}. Nothing was persisted. */
    private static void refuseSecret(HttpServletResponse response, IllegalStateException refused) throws IOException {
        text(response, 400, refused.getMessage());
    }

    /** A change, made as {@code actor}. */
    @FunctionalInterface
    private interface Change {
        void apply(SettingsEditor.Actor actor) throws IOException;
    }

    /** Make a change through the settings editor, answering {@code 200}; a refused one answers {@code 409} when what
     *  it sets is pinned above the store and {@code 400} otherwise - a value the catalogue refuses, a deployment that
     *  would not resolve, a secret this deployment cannot seal - with the editor's own sentence. Nothing was written
     *  then. */
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

    /** One runtime setting for the API: its key, its {@link Setting.Kind kind} (so a client masks a SECRET and picks
     *  the right control), its effective value and its file/env default, whether a stored override is in force,
     *  whether a change applies live, whether it is pinned from above the store (with the phrase naming the pin), and
     *  its {@link Setting.Tier tier} - whether a wizard asks it, a settings screen shows it, or folds it away.
     *  A SECRET's {@code value} and {@code defaultValue} are always {@code null} - the value is write-only and never
     *  read back - while {@code overridden}/{@code pinned} still say whether it is set. */
    public record SettingView(String key, String kind, String value, String defaultValue, boolean overridden,
                              boolean appliesImmediately, boolean pinned, String pinnedBy,
                              String group, String label, String description, boolean advanced, String tier) {
    }

    /** The first-run setup guide as the API serves it: the steps, each with the rows it is about. */
    public record SetupView(List<StepView> steps) {
    }

    /** One step of the wizard: its id and title, what it says - the starter credential's step, which asks no
     *  setting - and the settings rows it asks, a settings group's, the same rows {@code GET /api/settings} lists,
     *  documentation included. */
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
