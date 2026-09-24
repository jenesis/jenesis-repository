package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing routes. After sign-in a user is routed by how many tenants they can reach: a
 * super-admin always goes to the instances list; a user with exactly one accessible tenant has it
 * selected automatically and lands on its projects (never seeing the tenant concept); anyone with
 * several picks one on the instances page.
 */
@Controller
public class HomeController {

    private final Memberships memberships;
    private final SessionCurrentTenant current;
    private final SetupWizard setup;

    public HomeController(Memberships memberships, SessionCurrentTenant current, SetupWizard setup) {
        this.memberships = memberships;
        this.current = current;
        this.setup = setup;
    }

    /** The root forwards to the console so a bare host lands on it, keeping {@code /} free of a functional route. */
    @GetMapping("/")
    public String root() {
        return "redirect:/console";
    }

    @GetMapping("/console")
    public String home(Authentication authentication, HttpSession session) throws IOException {
        if (!authenticated(authentication)) {
            return "redirect:/login";
        }
        // Where there is one tenant to be in, the reader is in it - a super-admin of a single-tenant deployment as
        // much as a member of one tenant - and it is chosen before anything else, so no screen the reader opens next
        // sends them back here for a choice with one answer.
        List<String> accessible = memberships.accessibleTo(authentication.getName(), hasSuperadmin(authentication));
        if (accessible.size() == 1) {
            current.select(accessible.get(0));
        }
        // A super-admin on the starter credential is guided first, once per session and while the dial is on -
        // decided here, on the landing, so nothing else in the console is ever gated by it (SetupWizard says why).
        // The dial is read last, so only a starter session pays it, and it is the settings document - one object
        // per module under a constant prefix, narrow by construction - never a read that grows with the store.
        if (setup.redirects(authentication, session)) {
            return "redirect:/setup";
        }
        return accessible.size() == 1 ? "redirect:/repositories" : "redirect:/instances";
    }


    private static boolean authenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static boolean hasSuperadmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
