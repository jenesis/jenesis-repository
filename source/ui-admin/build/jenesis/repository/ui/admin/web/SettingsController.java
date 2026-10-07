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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The deployment-settings screens, super-admin only: every runtime-editable setting by group, with its effective
 * value, default and override, set or cleared through the same documents {@code /api/settings} and the CLI edit. The
 * per-tenant screen ({@code /settings/tenant}) edits the tenant-overridable slice of the session's
 * {@link CurrentTenant}, as {@code /api/settings?tenant=} does. Binding names are explicit because the build compiles
 * without {@code -parameters}.
 */
@Controller
@ConsoleScreen
public class SettingsController {

    /** Reads an uploaded bundle. */
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
        return "settings";
    }

    /** Where the deployment fetches what it does not hold: the format upstreams, the credentials sent to private ones
     *  and the routing naming them, with the selected tenant's layer over them. Reads the settings documents only. */
    @GetMapping("/ui/settings/upstreams")
    public String upstreams(Model model) throws IOException {
        model.addAttribute("repositories", settings.repositories());
        model.addAttribute("upstreams", settings.upstreams());
        // The selected tenant's upstreams; a repository's own routing is edited on its overview.
        String tenant = current.name();
        model.addAttribute("routedTenant", tenant);
        model.addAttribute("tenantUpstreams", tenant == null ? Map.of() : settings.upstreams(tenant));
        model.addAttribute("suggestedUpstreams", settings.suggestedUpstreams());
        model.addAttribute("upstreamAuthHosts", settings.upstreamCredentialHosts());
        return "upstreams";
    }

    /** The deployment settings bundle: its download and the restore that replaces every stored setting. */
    @GetMapping("/ui/settings/backup")
    public String backup() {
        return "backup";
    }

    /** The modules console: every discovered module's installed and enabled state, its settings, and a toggle where it
     *  declares an enablement gate, saved through {@code /settings/save}; the row says whether it applies live or on
     *  restart. */
    @GetMapping("/ui/settings/modules")
    public String modules(Model model) throws IOException {
        // The memoised orphaned-data snapshot and its as-of instant, never a fresh store walk.
        model.addAttribute("modules", settings.modules());
        model.addAttribute("orphanScannedAt", settings.orphanScannedAt());
        return "modules";
    }

    /** Purges one absent module's orphaned data, the confirmed step after the dry-run counts the screen shows, through
     *  the primitive {@code POST /api/admin/purge} uses, audited the same way. An unknown module is a flash message. */
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


    /** Why a value would be refused for a setting, as plain text, or nothing when it would be taken: what every
     *  settings form asks as a field is left, so a refusal shows before the form is sent. It reads the catalogue and
     *  writes nothing, so any member signed in to the console may ask it. */
    @PostMapping(value = "/ui/check/setting", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String check(@RequestParam("key") String key,
                        @RequestParam(name = "value", defaultValue = "") String value) {
        return settings.check(key, value).orElse("");
    }

    /** The screens a save may return to; any other {@code return} lands on the first. */
    private static final Set<String> RETURNS = Set.of("/ui/settings");

    /** Sets or clears one deployment setting through the catalogue. */
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

    /** The selected tenant's settings: only the tenant-overridable keys, each resolved pin over tenant over deployment
     *  over default, with the deployment value shown as the baseline. */
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

    /** Restores the selected tenant's slice from an uploaded bundle, validated before anything is written. */
    @PostMapping("/ui/settings/tenant/import")
    public String importTenant(HttpServletRequest request, RedirectAttributes redirect) {
        // Outside the catch, so a missing selection bounces to the picker rather than reading as a bad bundle.
        String tenant = tenant();
        try {
            settings.importTenant(tenant, parse(bundle(request)));
            redirect.addFlashAttribute("message", "Imported the settings for tenant " + tenant + ".");
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", "Could not import the tenant settings bundle: " + e.getMessage());
        }
        return "redirect:/ui/settings/tenant";
    }

    /** The session's tenant; a missing selection throws, so {@code GlobalControllerAdvice} bounces to the picker. */
    private String tenant() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }

    /** Downloads the stored settings as one JSON bundle, as {@code /api/settings/export} does, with every secret key
     *  excluded. */
    @GetMapping("/ui/settings/export")
    public void export(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename=\"jenesis-settings.json\"");
        response.getOutputStream().write(settings.exportBundle());
    }

    /** Restores an uploaded settings bundle in full, validated before anything is written. */
    @PostMapping("/ui/settings/import")
    public String importBundle(HttpServletRequest request, RedirectAttributes redirect) {
        try {
            settings.importBundle(parse(bundle(request)));
            redirect.addFlashAttribute("message", "Imported the deployment settings.");
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", "Could not import the settings bundle: " + e.getMessage());
        }
        return "redirect:/ui/settings/backup";
    }

    /**
     * The most of an uploaded settings bundle that is read; a bundle is kilobytes of strings. Past it
     * {@link MultipartBody.Part} yields no value rather than a prefix, so a full restore is never applied partially.
     */
    private static final int BUNDLE_LIMIT = 4 * 1024 * 1024;

    /**
     * The uploaded bundle, read with the product's bounded multipart reader: Spring's {@code MultipartResolver} is off in
     * every app, since it would drain a format's multipart upload (PyPI, NuGet) before its handler. For the same reason
     * the CSRF token cannot be read from the body, so both import forms carry it in their action URL.
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

    /** Parses a bundle (module to its flat {@code string -> string} document), accepting a number or boolean as its
     *  text. */
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
        settings.setRepository(null, name, definition);
        redirect.addFlashAttribute("message", "Saved repository '" + name + "'.");
        return "redirect:/ui/settings/upstreams";
    }

    @PostMapping("/ui/settings/repositories/remove")
    public String removeRepository(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        settings.removeRepository(null, name);
        redirect.addFlashAttribute("message", "Removed repository '" + name + "'.");
        return "redirect:/ui/settings/upstreams";
    }

    /** Sets a format's upstream: the deployment's, or with {@code forTenant} the session tenant's. */
    @PostMapping("/ui/settings/upstreams")
    public String setUpstream(@RequestParam("format") String format,
                              @RequestParam("url") String url,
                              @RequestParam(name = "forTenant", defaultValue = "false") boolean forTenant,
                              RedirectAttributes redirect) throws IOException {
        settings.setUpstream(forTenant ? current.name() : null, format, url);
        redirect.addFlashAttribute("message", "Saved upstream for '" + format + "'"
                + (forTenant ? " for tenant '" + current.name() + "'." : "."));
        return "redirect:/ui/settings/upstreams";
    }

    @PostMapping("/ui/settings/upstreams/remove")
    public String removeUpstream(@RequestParam("format") String format,
                                 @RequestParam(name = "forTenant", defaultValue = "false") boolean forTenant,
                                 RedirectAttributes redirect) throws IOException {
        settings.removeUpstream(forTenant ? current.name() : null, format);
        redirect.addFlashAttribute("message", "Removed upstream for '" + format + "'"
                + (forTenant ? " for tenant '" + current.name() + "'." : "."));
        return "redirect:/ui/settings/upstreams";
    }

    @PostMapping("/ui/settings/upstream-auth")
    public String setUpstreamCredential(@RequestParam("host") String host,
                                        @RequestParam("scheme") String scheme,
                                        @RequestParam(name = "username", defaultValue = "") String username,
                                        @RequestParam(name = "password", defaultValue = "") String password,
                                        @RequestParam(name = "token", defaultValue = "") String token,
                                        @RequestParam(name = "header", defaultValue = "") String header,
                                        RedirectAttributes redirect) throws IOException {
        try {
            settings.setUpstreamCredential(host, scheme, username, password, token, header);
            redirect.addFlashAttribute("message", "Stored an upstream credential for '" + host + "'.");
        } catch (IllegalStateException unsealable) {
            // No key to seal it under, or no credential module: said on the screen, since the console's own handler
            // reads an IllegalStateException as a missing tenant and would land on the dashboard saying nothing.
            redirect.addFlashAttribute("error", unsealable.getMessage());
        }
        return "redirect:/ui/settings/upstreams";
    }

    @PostMapping("/ui/settings/upstream-auth/remove")
    public String removeUpstreamCredential(@RequestParam("host") String host, RedirectAttributes redirect)
            throws IOException {
        settings.removeUpstreamCredential(host);
        redirect.addFlashAttribute("message", "Removed the upstream credential for '" + host + "'.");
        return "redirect:/ui/settings/upstreams";
    }
}
