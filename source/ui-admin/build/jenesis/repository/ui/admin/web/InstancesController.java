package build.jenesis.repository.ui.admin.web;

import module java.base;
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

/**
 * The tenants screen. Every user can list the tenants they may reach and select one to work in; an env super-admin
 * additionally sees all tenants and creates and deletes them. The super-admin-only routes are enforced in the
 * security config; selection re-checks membership here. Binding names are explicit because the Jenesis javac step
 * does not emit {@code -parameters}.
 */
@Controller
public class InstancesController {

    private final TenantService tenants;
    private final TenantPurge purge;
    private final Memberships memberships;
    private final SessionCurrentTenant current;

    public InstancesController(TenantService tenants, TenantPurge purge, Memberships memberships,
                               SessionCurrentTenant current) {
        this.tenants = tenants;
        this.purge = purge;
        this.memberships = memberships;
        this.current = current;
    }

    @GetMapping("/ui/instances")
    public String list(Authentication authentication, Model model) throws IOException {
        boolean superadmin = hasSuperadmin(authentication);
        List<String> all = superadmin ? tenants.all() : memberships.accessibleTo(authentication.getName(), false);
        model.addAttribute("tenants", all);
        model.addAttribute("selected", current.name());
        model.addAttribute("superadmin", superadmin);
        return "instances";
    }

    @PostMapping("/ui/instances/select")
    public String select(@RequestParam("tenant") String tenant, Authentication authentication) {
        boolean superadmin = hasSuperadmin(authentication);
        if (!tenants.exists(tenant)) {
            throw new IllegalArgumentException("No such tenant '" + tenant + "'.");
        }
        if (!superadmin && memberships.roleIn(tenant, authentication.getName()).isEmpty()) {
            throw new IllegalArgumentException("You do not have access to tenant '" + tenant + "'.");
        }
        current.select(tenant);
        return "redirect:/ui/repositories";
    }

    @PostMapping("/ui/instances/create")
    public String create(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        tenants.create(name);           // records the tenant.create audit event in the domain (TenantService)
        redirect.addFlashAttribute("message",
                "Created tenant '" + name + "'. Select it, then add its first admin under Admin.");
        return "redirect:/ui/instances";
    }

    @PostMapping("/ui/instances/delete")
    public String delete(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        purge.delete(name);
        if (name.equals(current.name())) {
            current.clear();
        }
        redirect.addFlashAttribute("message", "Deleted tenant '" + name + "'.");
        return "redirect:/ui/instances";
    }

    private static boolean hasSuperadmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
