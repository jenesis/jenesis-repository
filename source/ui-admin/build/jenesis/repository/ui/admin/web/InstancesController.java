package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import build.jenesis.repository.ui.store.Eviction;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import build.jenesis.repository.ui.store.VolumeReclaim;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenants ("instances") screen. Every user can list the tenants they may reach and select one to
 * work in; an env super-admin additionally sees all tenants, creates and deletes them, and runs the
 * volume-wide disk reclaim (a cross-tenant concern, so it lives here rather than on any one tenant's
 * admin page). The super-admin-only routes are enforced in the security config; selection re-checks
 * membership here. Binding names are explicit because the Jenesis javac step does not emit
 * {@code -parameters}.
 */
@Controller
public class InstancesController {

    private final TenantService tenants;
    private final TenantPurge purge;
    private final Memberships memberships;
    private final SessionCurrentTenant current;
    private final Documents rootStorage;
    private final UiProperties properties;
    private final Format format;
    private final VolumeReclaim volumeReclaim;

    public InstancesController(TenantService tenants, TenantPurge purge, Memberships memberships,
                               SessionCurrentTenant current,
                               @Qualifier("rootStorage") Documents rootStorage, UiProperties properties,
                               Format format, VolumeReclaim volumeReclaim) {
        this.tenants = tenants;
        this.purge = purge;
        this.memberships = memberships;
        this.current = current;
        this.rootStorage = rootStorage;
        this.properties = properties;
        this.format = format;
        this.volumeReclaim = volumeReclaim;
    }

    @GetMapping("/instances")
    public String list(Authentication authentication, Model model) throws IOException {
        boolean superadmin = hasSuperadmin(authentication);
        List<String> all = superadmin ? tenants.all() : memberships.accessibleTo(authentication.getName(), false);
        model.addAttribute("tenants", all);
        model.addAttribute("selected", current.name());
        model.addAttribute("superadmin", superadmin);
        if (superadmin) {
            // The volume is the STORE's, and it is asked directly rather than through a third interface. These
            // two used to come off the borrowed cache API, which computed them from exactly this call and kept its
            // "no volume" answers: usable is unbounded and total is zero when the backend has none to report.
            Optional<ArtifactStore.Capacity> capacity = rootStorage.store().capacity();
            model.addAttribute("usableSpace", capacity.map(ArtifactStore.Capacity::usable).orElse(Long.MAX_VALUE));
            model.addAttribute("totalSpace", capacity.map(ArtifactStore.Capacity::total).orElse(0L));
            model.addAttribute("minFreeBytes", properties.getMinFreeBytes());
            model.addAttribute("minFreePercent", properties.getMinFreePercent());
        }
        return "instances";
    }

    @PostMapping("/instances/select")
    public String select(@RequestParam("tenant") String tenant, Authentication authentication) {
        boolean superadmin = hasSuperadmin(authentication);
        if (!tenants.exists(tenant)) {
            throw new IllegalArgumentException("No such tenant '" + tenant + "'.");
        }
        if (!superadmin && memberships.roleIn(tenant, authentication.getName()).isEmpty()) {
            throw new IllegalArgumentException("You do not have access to tenant '" + tenant + "'.");
        }
        current.select(tenant);
        return "redirect:/repositories";
    }

    @PostMapping("/instances/create")
    public String create(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        tenants.create(name);           // records the tenant.create audit event in the domain (TenantService)
        redirect.addFlashAttribute("message",
                "Created tenant '" + name + "'. Select it, then add its first admin under Admin.");
        return "redirect:/instances";
    }

    @PostMapping("/instances/delete")
    public String delete(@RequestParam("name") String name, RedirectAttributes redirect) throws IOException {
        purge.delete(name);
        if (name.equals(current.name())) {
            current.clear();
        }
        redirect.addFlashAttribute("message", "Deleted tenant '" + name + "'.");
        return "redirect:/instances";
    }

    @PostMapping("/instances/reclaim")
    public String reclaim(@RequestParam(name = "minFreeBytes", required = false) Long minFreeBytes,
                          @RequestParam(name = "minFreePercent", required = false) Integer minFreePercent,
                          RedirectAttributes redirect) {
        long bytes = minFreeBytes != null ? minFreeBytes : properties.getMinFreeBytes();
        int percent = minFreePercent != null ? minFreePercent : properties.getMinFreePercent();
        // The reclaim spans every tenant's cache, so the mutation and its operator-scope audit live in the domain
        // (VolumeReclaim); the controller only resolves the effective thresholds and renders the outcome.
        Eviction.Result result = volumeReclaim.reclaim(bytes, percent);
        String suffix = result.entriesDeleted() == 0 ? " (target already met or no thresholds set)" : "";
        redirect.addFlashAttribute("message", "Global reclaim: deleted " + result.entriesDeleted()
                + " entries, freed " + format.bytes(result.bytesFreed()) + suffix + ".");
        return "redirect:/instances";
    }

    private static boolean hasSuperadmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
