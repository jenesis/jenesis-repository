package build.jenesis.repository.ui.admin.web;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.ui.store.SettingsAdmin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The deployment-settings screen: the super-admin reviews every runtime-editable setting grouped by area, with its
 * effective value, its default and whether an override is in force, and sets or clears one. It edits the same
 * {@code config/settings} object the {@code /api/settings} endpoint and the CLI drive, so the three administration
 * surfaces stay equal. Super-admin only (a deployment-wide concern, like the tenant lifecycle) - enforced in the
 * security config. Binding names are explicit because the Jenesis javac step does not emit {@code -parameters}.
 *
 * <p>The deployment-wide screens are joined by a per-tenant one ({@code /settings/tenant}): the same store of truth,
 * but the tenant-overridable slice (the gate policy, deny list and forward targets a tenant may retune) of the tenant
 * the session has selected - the console equivalent of {@code /api/settings?tenant=}. It reads the session {@link
 * CurrentTenant} the way {@code RepositoryAdmin} and {@code AuditController} do, so a super-admin retunes one tenant's
 * slice by selecting it, layered over the deployment default and leaving other tenants untouched.
 */
@Controller
public class SettingsController {

    /** The uploaded bundle is parsed with the framework's JSON reader, not the internal flat-document codec: an import
     *  is operator-supplied, so a real parser reads it. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SettingsAdmin settings;
    private final CurrentTenant current;
    private final Environment environment;

    public SettingsController(SettingsAdmin settings, CurrentTenant current, Environment environment) {
        this.settings = settings;
        this.current = current;
        this.environment = environment;
    }

    @GetMapping("/ui/settings")
    public String list(Model model) throws IOException {
        model.addAttribute("groups", settings.groups());
        model.addAttribute("repositories", settings.repositories());
        model.addAttribute("upstreams", settings.upstreams());
        model.addAttribute("suggestedUpstreams", settings.suggestedUpstreams());
        model.addAttribute("upstreamAuthHosts", settings.upstreamCredentialHosts());
        return "settings";
    }

    /** The modules console: the installed and enabled state of every discovered module, its contributed settings beneath
     *  it, and an enable/disable toggle where a module declares an enablement gate. The toggle posts to
     *  {@code /settings/save}, the same write path the settings screen uses, so a live gate applies on the nodes' next
     *  re-read and a restart-bound one on their next boot (the row says which). Super-admin, under {@code /settings/**}. */
    @GetMapping("/ui/settings/modules")
    public String modules(Model model) throws IOException {
        // Bind to the memoised orphaned-data snapshot (Principle 10: the render reads stored derived state, never a
        // fresh deployment-wide store walk per render) and show its as-of instant, read right after so it reflects
        // the same snapshot the rows carry.
        model.addAttribute("modules", settings.modules());
        model.addAttribute("orphanScannedAt", settings.orphanScannedAt());
        return "modules";
    }

    /** Purge one absent module's orphaned data - the button beside the modules screen's orphaned-data badge, driving
     *  the same manifest primitive as {@code jenesis-repo purge} / {@code POST /api/admin/purge} and audited the same way.
     *  The screen already shows the dry-run counts, so this is the confirmed second step; a module no manifest entry
     *  names answers with a flash message rather than a error page. Super-admin, under {@code /settings/**}. */
    @PostMapping("/ui/settings/modules/purge")
    public String purgeOrphanedData(@RequestParam("module") String module, RedirectAttributes redirect)
            throws IOException {
        Optional<StorageNamespaces.Report> report = settings.purgeOrphanedData(module);
        redirect.addFlashAttribute("message", report
                .map(purged -> "Purged " + purged.objects() + " object(s), " + purged.bytes() + " byte(s) of "
                        + module + "'s orphaned data.")
                .orElse("No storage-manifest entry names " + module + "; nothing was purged."));
        return "redirect:/ui/settings/modules";
    }


    /** The console screens a save may come back to: this screen, and the first-run setup guide, which posts its
     *  saves here so that a value applied from the guide is applied by exactly the path this screen applies it. Any
     *  other {@code return} lands here - a redirect target is never taken from the request unvetted. */
    private static final Set<String> RETURNS = Set.of("/ui/settings", "/ui/setup");

    @PostMapping("/ui/settings/save")
    public String save(@RequestParam("key") String key,
                       @RequestParam(name = "value", defaultValue = "") String value,
                       @RequestParam(name = "return", defaultValue = "/ui/settings") String back,
                       RedirectAttributes redirect) throws IOException {
        settings.save(key, value);
        redirect.addFlashAttribute("message",
                value.isBlank() ? "Cleared " + key + "; reverted to its default." : "Updated " + key + ".");
        return "redirect:" + (RETURNS.contains(back) ? back : "/ui/settings");
    }

    /** The selected tenant's runtime-settings screen: only the tenant-overridable keys (the gate policy, deny list and
     *  forward targets a tenant may retune), each along the chain <em>pin &gt; tenant document &gt; global document &gt;
     *  default</em>, with the global effective value shown as the tenant's baseline and whether this tenant has
     *  overridden it. The console equivalent of {@code /api/settings?tenant=}, scoped to the session tenant the way the
     *  repository and audit screens are. Super-admin, under {@code /settings/**}. */
    @GetMapping("/ui/settings/tenant")
    public String tenantSettings(Model model) throws IOException {
        model.addAttribute("groups", settings.groups(tenant()));
        return "tenant-settings";
    }

    @PostMapping("/ui/settings/tenant/save")
    public String saveTenant(@RequestParam("key") String key,
                             @RequestParam(name = "value", defaultValue = "") String value,
                             RedirectAttributes redirect) throws IOException {
        String tenant = tenant();
        settings.save(tenant, key, value);
        redirect.addFlashAttribute("message", value.isBlank()
                ? "Cleared " + key + " for tenant " + tenant + "; reverted to the deployment value."
                : "Updated " + key + " for tenant " + tenant + ".");
        return "redirect:/ui/settings/tenant";
    }

    /** Restore the selected tenant's overridable slice from an uploaded bundle: parsed with the framework's JSON reader,
     *  validated (an unparseable value is refused before anything is written), then written as a full restore of that
     *  tenant's slice, leaving the deployment settings and every other tenant untouched. */
    @PostMapping("/ui/settings/tenant/import")
    public String importTenant(HttpServletRequest request, RedirectAttributes redirect) {
        // Resolve the tenant outside the catch so a missing selection bounces to the picker rather than reading as a
        // bad bundle; only the import itself turns a storage/validation failure into a flash message.
        String tenant = tenant();
        try {
            settings.importTenant(tenant, parse(bundle(request)));
            redirect.addFlashAttribute("message", "Imported the settings for tenant " + tenant + ".");
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", "Could not import the tenant settings bundle: " + e.getMessage());
        }
        return "redirect:/ui/settings/tenant";
    }

    /** The tenant the session has selected, the way {@code RepositoryAdmin}/{@code AuditController} resolve it; a
     *  missing selection throws so {@code GlobalControllerAdvice} bounces a super-admin to pick one first. */
    private String tenant() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }

    /** Download the deployment's stored settings as one JSON bundle, for backup or transfer - the same layout the
     *  {@code /api/settings/export} endpoint and the CLI emit. Credential-free by construction: every SECRET-kind key
     *  is excluded, so a stored secret (the keyless identity token) never travels in the downloaded backup. */
    @GetMapping("/ui/settings/export")
    public void export(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename=\"jenesis-settings.json\"");
        response.getOutputStream().write(settings.exportBundle());
    }

    /** Restore an uploaded settings bundle: parsed with the framework's JSON reader, validated (an unparseable value is
     *  refused before anything is written), then written document-by-document as a full restore. A malformed upload
     *  reports the reason rather than half-applying. */
    @PostMapping("/ui/settings/import")
    public String importBundle(HttpServletRequest request, RedirectAttributes redirect) {
        try {
            settings.importBundle(parse(bundle(request)));
            redirect.addFlashAttribute("message", "Imported the deployment settings.");
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", "Could not import the settings bundle: " + e.getMessage());
        }
        return "redirect:/ui/settings";
    }

    /**
     * The most of an uploaded settings bundle that is read. A bundle is <em>metadata</em> - a handful of documents of
     * {@code key -> value} strings, kilobytes even for a deployment with many tenants - not an artifact, so unlike an
     * upload to a format it gets a real cap. Reaching it is a visible, explicit refusal: {@link MultipartBody.Part}
     * yields no value at all past its bound rather than a prefix, so a bundle that ran over can never be imported
     * <em>partially</em> - which for a full-restore import would silently wipe every setting the truncation cut off.
     */
    private static final int BUNDLE_LIMIT = 4 * 1024 * 1024;

    /**
     * The uploaded bundle, read off the request body with the product's shared multipart reader rather than a bound
     * {@code MultipartFile}. Spring's {@code MultipartResolver} is switched off in <em>every</em> app because
     * it, and {@code FormContentFilter}, would drain an artifact upload body before the format handler read it - twine's
     * PyPI upload and {@code dotnet nuget push} are both {@code multipart/form-data} - so the console cannot depend on
     * it and used to be simply broken wherever it was off (the combined single-node image). The console now walks the
     * same bounded, streaming reader the NuGet and PyPI publish paths walk, which keeps that switch global and leaves
     * the browser form a plain file upload.
     *
     * <p>Because there is no resolver, Spring Security's CSRF token cannot be read out of the multipart body either
     * (nothing parses it before the filter chain runs), which is why both import forms carry the token in their action
     * URL - see {@code settings.html} / {@code tenant-settings.html}.
     */
    private static byte[] bundle(HttpServletRequest request) throws IOException {
        String missing = "Choose a settings bundle file to import.";
        String boundary = MultipartBody.boundary(request.getContentType())
                .orElseThrow(() -> new IllegalArgumentException(missing));
        byte[] content = MultipartBody.over(request.getInputStream(), boundary)
                .nextFile("bundle")
                .orElseThrow(() -> new IllegalArgumentException(missing))
                .bytes(BUNDLE_LIMIT)
                .orElseThrow(() -> new IllegalArgumentException(
                        "the bundle is larger than the " + (BUNDLE_LIMIT / (1024 * 1024)) + " MiB import limit"));
        if (content.length == 0) {
            throw new IllegalArgumentException(missing);
        }
        return content;
    }

    /** Parse an operator-supplied bundle (module name to that module's flat {@code string -> string} document) with a
     *  real JSON reader over the bounded upload, coercing scalar values to strings so a number or boolean is accepted
     *  as its text. */
    private static Map<String, Map<String, String>> parse(byte[] json) {
        if (!(JSON.readValue(json, Object.class) instanceof Map<?, ?> modules)) {
            throw new IllegalArgumentException("the bundle must be a JSON object of module documents");
        }
        Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> module : modules.entrySet()) {
            if (!(module.getValue() instanceof Map<?, ?> document)) {
                throw new IllegalArgumentException("module '" + module.getKey() + "' is not a settings document");
            }
            Map<String, String> values = new LinkedHashMap<>();
            document.forEach((key, value) ->
                    values.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
            parsed.put(String.valueOf(module.getKey()), values);
        }
        return parsed;
    }

    @PostMapping("/ui/settings/repositories")
    public String setRepository(@RequestParam("name") String name,
                                @RequestParam("definition") String definition,
                                RedirectAttributes redirect) throws IOException {
        settings.setRepository(name, definition);
        redirect.addFlashAttribute("message", "Saved repository '" + name + "'.");
        return "redirect:/ui/settings";
    }

    @PostMapping("/ui/settings/repositories/remove")
    public String removeRepository(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        settings.removeRepository(name);
        redirect.addFlashAttribute("message", "Removed repository '" + name + "'.");
        return "redirect:/ui/settings";
    }

    @PostMapping("/ui/settings/upstreams")
    public String setUpstream(@RequestParam("format") String format,
                              @RequestParam("url") String url,
                              RedirectAttributes redirect) throws IOException {
        settings.setUpstream(format, url);
        redirect.addFlashAttribute("message", "Saved upstream for '" + format + "'.");
        return "redirect:/ui/settings";
    }

    @PostMapping("/ui/settings/upstreams/remove")
    public String removeUpstream(@RequestParam("format") String format, RedirectAttributes redirect)
            throws IOException {
        settings.removeUpstream(format);
        redirect.addFlashAttribute("message", "Removed upstream for '" + format + "'.");
        return "redirect:/ui/settings";
    }

    @PostMapping("/ui/settings/upstream-auth")
    public String setUpstreamCredential(@RequestParam("host") String host,
                                        @RequestParam("scheme") String scheme,
                                        @RequestParam(name = "username", defaultValue = "") String username,
                                        @RequestParam(name = "password", defaultValue = "") String password,
                                        @RequestParam(name = "token", defaultValue = "") String token,
                                        @RequestParam(name = "header", defaultValue = "") String header,
                                        RedirectAttributes redirect) throws IOException {
        settings.setUpstreamCredential(host, scheme, username, password, token, header);
        redirect.addFlashAttribute("message", "Stored an upstream credential for '" + host + "'.");
        return "redirect:/ui/settings";
    }

    @PostMapping("/ui/settings/upstream-auth/remove")
    public String removeUpstreamCredential(@RequestParam("host") String host, RedirectAttributes redirect)
            throws IOException {
        settings.removeUpstreamCredential(host);
        redirect.addFlashAttribute("message", "Removed the upstream credential for '" + host + "'.");
        return "redirect:/ui/settings";
    }
}
