package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The tenants screen. Every user can list the tenants they may reach and select one to work in; an env super-admin
 * additionally sees all tenants and creates and deletes them. The super-admin-only routes are enforced in the
 * security config; selection re-checks membership here. Binding names are explicit because the Jenesis javac step
 * does not emit {@code -parameters}.
 */
@Controller
@ConsoleScreen
public class TenantsController {

    private final TenantService tenants;
    private final TenantPurge purge;
    private final Memberships memberships;
    private final SessionCurrentTenant current;

    public TenantsController(TenantService tenants, TenantPurge purge, Memberships memberships,
                               SessionCurrentTenant current) {
        this.tenants = tenants;
        this.purge = purge;
        this.memberships = memberships;
        this.current = current;
    }

    @GetMapping("/ui/tenants")
    public String list(Authentication authentication, Model model) throws IOException {
        boolean superadmin = hasSuperadmin(authentication);
        List<String> all = superadmin ? tenants.all() : memberships.accessibleTo(authentication.getName(), false);
        model.addAttribute("tenants", all);
        model.addAttribute("selected", current.name());
        model.addAttribute("superadmin", superadmin);
        return "tenants";
    }

    /**
     * Select the tenant the console works in. A member's membership is asked before anything else, so a tenant they
     * are not a member of is answered the same whether it exists or not - as an absent one, unless the deployment's
     * {@link AccessDenial} says to refuse it honestly - and costs the same one membership read either way. Only a
     * super-admin, who may select any tenant, is told a tenant does not exist.
     */
    @PostMapping("/ui/tenants/select")
    public String select(@RequestParam("tenant") String tenant, Authentication authentication) {
        String absent = "No such tenant '" + tenant + "'.";
        if (!hasSuperadmin(authentication)) {
            if (memberships.roleIn(tenant, authentication.getName()).isEmpty()) {
                throw new IllegalArgumentException(AccessDenial.configured()
                        .explain(absent, "You do not have access to tenant '" + tenant + "'."));
            }
        } else if (!tenants.exists(tenant)) {
            throw new IllegalArgumentException(absent);
        }
        current.select(tenant);
        return "redirect:/ui/repositories";
    }

    @PostMapping("/ui/tenants/create")
    public String create(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        tenants.create(name);           // records the tenant.create audit event in the domain (TenantService)
        redirect.addFlashAttribute("message",
                "Created tenant '" + name + "'. Select it, then add its first admin under Admin.");
        return "redirect:/ui/tenants";
    }

    @PostMapping("/ui/tenants/delete")
    public String delete(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        purge.delete(name);
        if (name.equals(current.name())) {
            current.clear();
        }
        redirect.addFlashAttribute("message", "Deleted tenant '" + name + "'.");
        return "redirect:/ui/tenants";
    }

    private static boolean hasSuperadmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
