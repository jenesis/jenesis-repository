package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.server.spi.CredentialLifetimes;
import build.jenesis.repository.ui.store.CredentialService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * Manages access credentials through the console: list them, mint a new one (its key is shown once), grant or
 * revoke a role on a project ({@code *} for all) over the discovered rights surfaces, set or clear a key's expiry, and
 * delete a credential; mutations need admin in the selected tenant. An expiry is an ISO-8601 duration from now
 * ({@code P30D}) or instant; a blank one on a mint applies the default lifetime unless "never" is ticked. Binding names
 * are explicit because the build compiles without {@code -parameters}.
 */
@Controller
@ConsoleScreen
public class CredentialsController {

    private final CredentialService credentials;

    public CredentialsController(CredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping("/ui/credentials")
    public String list(@RequestParam(name = "after", defaultValue = "") String after, Model model)
            throws IOException {
        CredentialService.Page page = credentials.list(after.isBlank() ? null : after, CredentialService.PAGE);
        model.addAttribute("credentials", page.credentials());
        model.addAttribute("next", page.next());
        model.addAttribute("paged", !after.isBlank());
        return "credentials";
    }

    /** The form a new credential is created with; its key is shown on the credential's page, once. */
    @GetMapping("/ui/credentials/new")
    public String create() {
        return "credential-new";
    }

    /** What governs every credential of the tenant: the lifetime policy, leak detection and the roles. */
    @GetMapping("/ui/credentials/policies")
    public String policies(Model model) throws IOException {
        model.addAttribute("policy", credentials.policy());
        model.addAttribute("roles", credentials.roles());
        model.addAttribute("rights", credentials.availableRights());
        return "credential-policies";
    }

    /** The OIDC trusts a CI job exchanges its platform's token through for a short-lived key. */
    @GetMapping("/ui/credentials/keyless")
    public String keyless(Model model) throws IOException {
        model.addAttribute("trusts", credentials.trusts());
        return "credential-keyless";
    }

    @PostMapping("/ui/credentials/roles")
    public String setRole(@RequestParam("name") String name,
                          @RequestParam("tokens") String tokens,
                          RedirectAttributes redirect) throws IOException {
        credentials.setRole(name, tokens);
        redirect.addFlashAttribute("message", "Saved role '" + name + "'.");
        return "redirect:/ui/credentials/policies";
    }

    @PostMapping("/ui/credentials/roles/{name}/remove")
    public String removeRole(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        credentials.removeRole(name);
        redirect.addFlashAttribute("message", "Removed role '" + name + "'.");
        return "redirect:/ui/credentials/policies";
    }

    @PostMapping("/ui/credentials/trusts")
    public String setTrust(@RequestParam("name") String name,
                           @RequestParam("issuer") String issuer,
                           @RequestParam(name = "audience", required = false) String audience,
                           @RequestParam(name = "subject", required = false) String subject,
                           @RequestParam("scope") String scope,
                           @RequestParam("rights") String rights,
                           @RequestParam(name = "ttl", required = false) String ttl,
                           RedirectAttributes redirect) throws IOException {
        credentials.setTrust(name, issuer, audience, subject, scope, rights, CredentialLifetimes.lifetime(ttl));
        redirect.addFlashAttribute("message", "Saved OIDC trust '" + name + "'.");
        return "redirect:/ui/credentials/keyless";
    }

    @PostMapping("/ui/credentials/trusts/{name}/remove")
    public String removeTrust(@PathVariable("name") String name, RedirectAttributes redirect) throws IOException {
        credentials.removeTrust(name);
        redirect.addFlashAttribute("message", "Removed OIDC trust '" + name + "'.");
        return "redirect:/ui/credentials/keyless";
    }

    @PostMapping("/ui/credentials/policy")
    public String setPolicy(@RequestParam(name = "default", required = false) String defaultLifetime,
                            @RequestParam(name = "max", required = false) String max,
                            RedirectAttributes redirect) throws IOException {
        credentials.setPolicy(CredentialLifetimes.lifetime(defaultLifetime), CredentialLifetimes.lifetime(max));
        redirect.addFlashAttribute("message", "Updated the credential-lifetime policy.");
        return "redirect:/ui/credentials/policies";
    }

    @PostMapping("/ui/credentials")
    public String create(@RequestParam(name = "label", required = false) String label,
                         @RequestParam(name = "expires", required = false) String expires,
                         @RequestParam(name = "never", required = false, defaultValue = "false") boolean never,
                         RedirectAttributes redirect) throws IOException {
        CredentialService.Created created = credentials.create(label, CredentialLifetimes.expiry(expires), never);
        String lifetime = created.expires() == null
                ? " This key never expires; prefer a finite lifetime and rotate it regularly."
                : " It expires on " + created.expires() + ".";
        redirect.addFlashAttribute("message",
                "Created credential. Copy its key now - it is shown only once: " + created.key() + lifetime);
        return "redirect:/ui/credentials/" + created.id();
    }

    @GetMapping("/ui/credentials/{id}")
    public String detail(@PathVariable("id") String id, Model model) throws IOException {
        model.addAttribute("credential", credentials.get(id));
        model.addAttribute("roles", credentials.roleNames());
        return "credential";
    }

    @PostMapping("/ui/credentials/{id}/grants")
    public String setGrant(@PathVariable("id") String id,
                           @RequestParam("project") String project,
                           @RequestParam(name = "path", required = false) String path,
                           @RequestParam(name = "role", defaultValue = "cache:read") String role,
                           RedirectAttributes redirect) throws IOException {
        credentials.setGrant(id, project, path, role);
        String where = path == null || path.isBlank() ? "'" + project + "'" : "'" + project + "' under " + path.trim();
        redirect.addFlashAttribute("message", "Granted " + role + " on " + where + ".");
        return "redirect:/ui/credentials/" + id;
    }

    @PostMapping("/ui/credentials/{id}/grants/remove")
    public String removeGrant(@PathVariable("id") String id,
                              @RequestParam("project") String project,
                              RedirectAttributes redirect) throws IOException {
        credentials.removeGrant(id, project);
        redirect.addFlashAttribute("message", "Revoked access to '" + project + "'.");
        return "redirect:/ui/credentials/" + id;
    }

    @PostMapping("/ui/credentials/{id}/expiry")
    public String setExpiry(@PathVariable("id") String id,
                            @RequestParam(name = "expires", required = false) String expires,
                            RedirectAttributes redirect) throws IOException {
        Instant expiry = CredentialLifetimes.expiry(expires);
        credentials.setExpiry(id, expiry);
        redirect.addFlashAttribute("message", expiry == null ? "Cleared expiry." : "Expires " + expiry + ".");
        return "redirect:/ui/credentials/" + id;
    }

    @PostMapping("/ui/credentials/{id}/rotate")
    public String rotate(@PathVariable("id") String id,
                         @RequestParam(name = "overlap", required = false) String overlap,
                         RedirectAttributes redirect) throws IOException {
        CredentialService.Created created = credentials.rotate(id, CredentialLifetimes.lifetime(overlap));
        redirect.addFlashAttribute("message",
                "Rotated. Copy the new key now - it is shown only once: " + created.key()
                        + " The previous key keeps working until the overlap elapses.");
        return "redirect:/ui/credentials/" + created.id();
    }

    @PostMapping("/ui/credentials/{id}/allowed-ips")
    public String setAllowedAddresses(@PathVariable("id") String id,
                                      @RequestParam(name = "addresses", required = false) String addresses,
                                      RedirectAttributes redirect) throws IOException {
        credentials.setAllowedAddresses(id, addresses);
        redirect.addFlashAttribute("message", addresses == null || addresses.isBlank()
                ? "Cleared the source-IP allowlist."
                : "Restricted the key to " + addresses.trim() + ".");
        return "redirect:/ui/credentials/" + id;
    }

    @PostMapping("/ui/credentials/{id}/delete")
    public String delete(@PathVariable("id") String id, RedirectAttributes redirect) throws IOException {
        credentials.delete(id);
        redirect.addFlashAttribute("message", "Deleted credential.");
        return "redirect:/ui/credentials";
    }
}
